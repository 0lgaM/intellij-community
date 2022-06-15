// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.compiler.configuration

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import com.intellij.project.stateStore
import com.intellij.util.io.exists
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.config.JpsPluginSettings
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.SettingConstants
import org.jetbrains.kotlin.config.SettingConstants.KOTLIN_JPS_PLUGIN_SETTINGS_SECTION
import org.jetbrains.kotlin.config.toKotlinVersion
import org.jetbrains.kotlin.idea.base.plugin.KotlinBasePluginBundle
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode

@State(name = KOTLIN_JPS_PLUGIN_SETTINGS_SECTION, storages = [(Storage(SettingConstants.KOTLIN_COMPILER_SETTINGS_FILE))])
class KotlinJpsPluginSettings(project: Project) : BaseKotlinCompilerSettings<JpsPluginSettings>(project) {
    override fun createSettings() = JpsPluginSettings()

    fun setVersion(jpsVersion: String) {
        if (jpsVersion == settings.version) return
        update { version = jpsVersion }
    }

    fun dropExplicitVersion(): Unit = setVersion("")

    companion object {
        // Use bundled by default because this will work even without internet connection
        @JvmStatic
        val rawBundledVersion: String get() = bundledVersion.rawVersion

        // Use stable 1.6.21 for outdated compiler versions in order to work with old LV settings
        @JvmStatic
        val fallbackVersionForOutdatedCompiler: String get() = "1.6.21"

        @JvmStatic
        val bundledVersion: IdeKotlinVersion get() = KotlinPluginLayout.standaloneCompilerVersion

        @JvmStatic
        val jpsMinimumSupportedVersion: KotlinVersion = IdeKotlinVersion.get("1.6.0").kotlinVersion

        @JvmStatic
        val jpsMaximumSupportedVersion: KotlinVersion = LanguageVersion.values().last().toKotlinVersion()

        fun validateSettings(project: Project) {
            val jpsPluginSettings = project.service<KotlinJpsPluginSettings>()
            if (!isUnbundledJpsExperimentalFeatureEnabled(project)) {
                // Delete compiler version in kotlinc.xml when feature flag is off
                jpsPluginSettings.dropExplicitVersion()
                return
            }

            if (jpsPluginSettings.settings.version.isEmpty() && bundledVersion.buildNumber == null) {
                // Encourage user to specify desired Kotlin compiler version in project settings for sake of reproducible builds
                // it's important to trigger `.idea/kotlinc.xml` file creation
                jpsPluginSettings.setVersion(rawBundledVersion)
            }
        }

        fun jpsVersion(project: Project): String? = getInstance(project)?.settings?.versionWithFallback

        @JvmStatic
        fun getInstance(project: Project): KotlinJpsPluginSettings? =
            project.takeIf { isUnbundledJpsExperimentalFeatureEnabled(it) }?.service()

        @JvmStatic
        fun isUnbundledJpsExperimentalFeatureEnabled(project: Project): Boolean = isUnitTestMode() || !project.isDefault

        /**
         * @param jpsVersion version to parse
         * @param fromFile true if [jpsVersion] come from kotlin.xml
         * @return error message if [jpsVersion] is not valid
         */
        @Nls
        fun checkJpsVersion(jpsVersion: String, fromFile: Boolean = false): UnsupportedJpsVersionError? {
            val parsedKotlinVersion = IdeKotlinVersion.opt(jpsVersion)?.kotlinVersion
            if (parsedKotlinVersion == null) {
                return ParsingError(
                    if (fromFile) {
                        KotlinBasePluginBundle.message(
                            "failed.to.parse.kotlin.version.0.from.1",
                            jpsVersion,
                            SettingConstants.KOTLIN_COMPILER_SETTINGS_FILE,
                        )
                    } else {
                        KotlinBasePluginBundle.message("failed.to.parse.kotlin.version.0", jpsVersion)
                    }
                )
            }

            if (parsedKotlinVersion < jpsMinimumSupportedVersion) {
                return OutdatedCompilerVersion(KotlinBasePluginBundle.message(
                    "kotlin.jps.compiler.minimum.supported.version.not.satisfied",
                    jpsMinimumSupportedVersion,
                    jpsVersion,
                ))

            }

            if (parsedKotlinVersion > jpsMaximumSupportedVersion) {
                return NewCompilerVersion(KotlinBasePluginBundle.message(
                    "kotlin.jps.compiler.maximum.supported.version.not.satisfied",
                    jpsMaximumSupportedVersion,
                    jpsVersion,
                ))
            }

            return null
        }

        fun supportedJpsVersion(project: Project, onUnsupportedVersion: (String) -> Unit): String? {
            val version = jpsVersion(project) ?: return null
            return when (val error = checkJpsVersion(version, fromFile = true)) {
                is OutdatedCompilerVersion -> fallbackVersionForOutdatedCompiler

                is NewCompilerVersion, is ParsingError -> {
                    onUnsupportedVersion(error.message)
                    null
                }

                null -> version
            }
        }

        fun updateAndDownloadOrDropVersion(
            project: Project,
            rawVersion: String,
            progressIndicator: ProgressIndicator = ProgressManager.getInstance().progressIndicator,
            showNotification: Boolean = true,
        ): Boolean {
            val instance = getInstance(project) ?: return true
            if (rawVersion == rawBundledVersion) {
                instance.setVersion(rawVersion)
                return true
            }

            val error = checkJpsVersion(rawVersion)
            val version = when (error) {
                is OutdatedCompilerVersion -> fallbackVersionForOutdatedCompiler
                is NewCompilerVersion, is ParsingError -> rawBundledVersion
                null -> rawVersion
            }

            if (error != null) {
                if (showNotification) {
                    showNotificationUnsupportedJpsPluginVersion(
                        project,
                        KotlinBasePluginBundle.message("notification.title.unsupported.kotlin.jps.plugin.version"),
                        KotlinBasePluginBundle.message(
                            "notification.content.bundled.version.0.will.be.used.reason.1",
                            version,
                            error.message
                        ),
                    )
                }

                if (error !is OutdatedCompilerVersion) {
                    instance.dropExplicitVersion()
                    return false
                }
            }

            val ok = KotlinArtifactsDownloader.lazyDownloadMissingJpsPluginDependencies(
                project = project,
                jpsVersion = version,
                indicator = progressIndicator,
                onError = {
                    if (showNotification) {
                        showNotificationUnsupportedJpsPluginVersion(
                            project,
                            KotlinBasePluginBundle.message("notification.title.jps.artifacts.were.not.found"),
                            KotlinBasePluginBundle.message(
                                "notification.content.bundled.version.0.will.be.used.reason.1",
                                rawBundledVersion,
                                it
                            ),
                        )
                    }
                },
            )

            if (ok) {
                instance.setVersion(version)
            } else {
                instance.dropExplicitVersion()
            }

            return ok
        }
    }
}

sealed class UnsupportedJpsVersionError(val message: String)
class ParsingError(message: String) : UnsupportedJpsVersionError(message)
class OutdatedCompilerVersion(message: String) : UnsupportedJpsVersionError(message)
class NewCompilerVersion(message: String) : UnsupportedJpsVersionError(message)

@get:NlsSafe
val JpsPluginSettings.versionWithFallback: String get() = version.ifEmpty { KotlinJpsPluginSettings.rawBundledVersion }

private fun showNotificationUnsupportedJpsPluginVersion(
    project: Project,
    @NlsContexts.NotificationTitle title: String,
    @NlsContexts.NotificationContent content: String,
) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Kotlin JPS plugin")
        .createNotification(title, content, NotificationType.WARNING)
        .setImportant(true)
        .notify(project)
}
