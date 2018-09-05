package com.intellij.configurationScript

import com.google.gson.Gson
import com.intellij.execution.application.ApplicationConfigurationOptions
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.assertions.Assertions.assertThat
import com.intellij.util.SmartList
import com.intellij.util.text.CharSequenceReader
import org.intellij.lang.annotations.Language
import org.junit.ClassRule
import org.junit.Test
import javax.swing.Icon

class ConfigurationFileTest {
  companion object {
    @JvmField
    @ClassRule
    val projectRule = ProjectRule()
  }

  @Test
  fun schema() {
    // check that parseable
    val schema = generateConfigurationSchema()
    val jsonReader = Gson().fromJson(CharSequenceReader(schema), Any::class.java)
    assertThat(jsonReader).isNotNull
  }

  @Test
  fun rcId() {
    fun convert(string: String): String {
      return rcTypeIdToPropertyName(TestConfigurationType(string)).toString()
    }

    assertThat(convert("foo")).isEqualTo("foo")
    assertThat(convert("Foo")).isEqualTo("foo")
    assertThat(convert("foo-bar")).isEqualTo("fooBar")
    assertThat(convert("foo.bar")).isEqualTo("fooBar")
    assertThat(convert("foo_bar")).isEqualTo("fooBar")
    assertThat(convert("FOO")).isEqualTo("foo")
    assertThat(convert("_FOO")).isEqualTo("foo")
    // better will be barFoo but for now we don't support this strange case
    @Suppress("SpellCheckingInspection")
    assertThat(convert("BAR_FOO")).isEqualTo("barfoo")
  }

  @Test
  fun empty() {
    val result = parse("""
    runConfigurations:
    """)
    assertThat(result).isEmpty()
  }

  @Test
  fun `empty rc type group`() {
    val result = parse("""
    runConfigurations:
      jvmMainMethod:
    """)
    assertThat(result).isEmpty()
  }

  @Test
  fun `empty rc`() {
    val result = parse("""
    runConfigurations:
      jvmMainMethod:
        -
    """)
    assertThat(result).isEmpty()
  }

  @Test
  fun `one jvmMainMethod`() {
    val result = parse("""
    runConfigurations:
      jvmMainMethod:
        isAlternativeJrePathEnabled: true
    """)
    val options = ApplicationConfigurationOptions()
    options.isAlternativeJrePathEnabled = true
    assertThat(result).containsExactly(options)
  }

  @Test
  fun `one jvmMainMethod as list`() {
    val result = parse("""
    runConfigurations:
      jvmMainMethod:
        - isAlternativeJrePathEnabled: true
    """)
    val options = ApplicationConfigurationOptions()
    options.isAlternativeJrePathEnabled = true
    assertThat(result).containsExactly(options)
  }
}

private fun parse(@Language("YAML") data: String): List<Any> {
  val list = SmartList<Any>()
  parseConfigurationFile(data.trimIndent().reader()) { _, state ->
    list.add(state)
  }
  return list
}

private class TestConfigurationType(id: String) : ConfigurationTypeBase(id, id, "", null as Icon?)