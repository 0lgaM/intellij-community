/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInsight.daemon.QuickFixActionRegistrar;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.daemon.impl.actions.AddImportAction;
import com.intellij.codeInsight.daemon.quickFix.ExternalLibraryDescriptor;
import com.intellij.codeInsight.daemon.quickFix.ExternalLibraryResolver;
import com.intellij.codeInsight.daemon.quickFix.ExternalLibraryResolver.ExternalClassResolveResult;
import com.intellij.codeInsight.daemon.quickFix.MissingDependencyFixProvider;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.EffectiveLanguageLevelUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.impl.OrderEntryUtil;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packageDependencies.DependencyValidationManager;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Function;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.intellij.codeInsight.daemon.impl.quickfix.MissingDependencyFixUtil.findFixes;
import static com.intellij.codeInsight.daemon.impl.quickfix.MissingDependencyFixUtil.provideFix;

/**
 * @author cdr
 */
public abstract class OrderEntryFix implements IntentionAction, LocalQuickFix {
  protected OrderEntryFix() {
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }

  @Override
  @NotNull
  public String getName() {
    return getText();
  }

  @Override
  public void applyFix(@NotNull final Project project, @NotNull final ProblemDescriptor descriptor) {
    invoke(project, null, descriptor.getPsiElement().getContainingFile());
  }

  @Nullable
  public static List<LocalQuickFix> registerFixes(@NotNull final QuickFixActionRegistrar registrar, @NotNull final PsiReference reference) {
    final PsiElement psiElement = reference.getElement();
    @NonNls final String shortReferenceName = reference.getRangeInElement().substring(psiElement.getText());

    Project project = psiElement.getProject();
    PsiFile containingFile = psiElement.getContainingFile();
    if (containingFile == null) return null;

    final VirtualFile classVFile = containingFile.getVirtualFile();
    if (classVFile == null) return null;

    final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
    final Module currentModule = fileIndex.getModuleForFile(classVFile);
    if (currentModule == null) return null;

    final List<LocalQuickFix> providedFixes = findFixes(new Function<MissingDependencyFixProvider, List<LocalQuickFix>>() {
      @Override
      public List<LocalQuickFix> fun(MissingDependencyFixProvider provider) {
        return provider.registerFixes(registrar, reference);
      }
    });
    if (providedFixes != null) {
      return providedFixes;
    }

    List<LocalQuickFix> result = new ArrayList<LocalQuickFix>();
    JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    String fullReferenceText = reference.getCanonicalText();
    for (ExternalLibraryResolver resolver : ExternalLibraryResolver.EP_NAME.getExtensions()) {
      final ExternalClassResolveResult resolveResult = resolver.resolveClass(shortReferenceName, isReferenceToAnnotation(psiElement));
      OrderEntryFix fix = null;
      if (resolveResult != null && psiFacade.findClass(resolveResult.getQualifiedClassName(), currentModule.getModuleWithDependenciesAndLibrariesScope(true)) == null) {
        fix = new AddExternalLibraryToDependenciesQuickFix(currentModule, resolveResult.getLibrary(), reference, resolveResult.getQualifiedClassName());
      }
      else if (!fullReferenceText.equals(shortReferenceName)) {
        ExternalLibraryDescriptor descriptor = resolver.resolvePackage(fullReferenceText);
        if (descriptor != null) {
          fix = new AddExternalLibraryToDependenciesQuickFix(currentModule, descriptor, reference, null);
        }
      }
      if (fix != null) {
        registrar.register(fix);
        result.add(fix);
      }
    }
    if (!result.isEmpty()) {
      return result;
    }

    Set<Object> librariesToAdd = new THashSet<Object>();
    final JavaPsiFacade facade = JavaPsiFacade.getInstance(psiElement.getProject());
    PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(shortReferenceName, GlobalSearchScope.allScope(project));
    List<PsiClass> allowedDependencies = filterAllowedDependencies(psiElement, classes);
    if (allowedDependencies.isEmpty()) {
      return result;
    }
    classes = allowedDependencies.toArray(new PsiClass[allowedDependencies.size()]);
    OrderEntryFix moduleDependencyFix = new AddModuleDependencyFix(currentModule, classVFile, classes, reference);

    final PsiClass[] finalClasses = classes;
    final OrderEntryFix finalModuleDependencyFix = moduleDependencyFix;
    final OrderEntryFix providedModuleDependencyFix = provideFix(new Function<MissingDependencyFixProvider, OrderEntryFix>() {
      @Override
      public OrderEntryFix fun(MissingDependencyFixProvider provider) {
        return provider.getAddModuleDependencyFix(reference, finalModuleDependencyFix, currentModule, classVFile, finalClasses);
      }
    });
    moduleDependencyFix = ObjectUtils.notNull(providedModuleDependencyFix, moduleDependencyFix);

    registrar.register(moduleDependencyFix);
    result.add(moduleDependencyFix);
    for (final PsiClass aClass : classes) {
      if (!facade.getResolveHelper().isAccessible(aClass, psiElement, aClass)) continue;
      PsiFile psiFile = aClass.getContainingFile();
      if (psiFile == null) continue;
      VirtualFile virtualFile = psiFile.getVirtualFile();
      if (virtualFile == null) continue;
      ModuleFileIndex moduleFileIndex = ModuleRootManager.getInstance(currentModule).getFileIndex();
      for (OrderEntry orderEntry : fileIndex.getOrderEntriesForFile(virtualFile)) {
        if (orderEntry instanceof LibraryOrderEntry) {
          final LibraryOrderEntry libraryEntry = (LibraryOrderEntry)orderEntry;
          final Library library = libraryEntry.getLibrary();
          if (library == null) continue;
          VirtualFile[] files = library.getFiles(OrderRootType.CLASSES);
          if (files.length == 0) continue;
          final VirtualFile jar = files[0];

          if (jar == null || libraryEntry.isModuleLevel() && !librariesToAdd.add(jar) || !librariesToAdd.add(library)) continue;
          OrderEntry entryForFile = moduleFileIndex.getOrderEntryForFile(virtualFile);
          if (entryForFile != null &&
              !(entryForFile instanceof ExportableOrderEntry &&
                ((ExportableOrderEntry)entryForFile).getScope() == DependencyScope.TEST &&
                !ModuleRootManager.getInstance(currentModule).getFileIndex().isInTestSourceContent(classVFile))) {
            continue;
          }
          final OrderEntryFix platformFix = new OrderEntryFix() {
            @Override
            @NotNull
            public String getText() {
              return QuickFixBundle.message("orderEntry.fix.add.library.to.classpath", libraryEntry.getPresentableName());
            }

            @Override
            @NotNull
            public String getFamilyName() {
              return QuickFixBundle.message("orderEntry.fix.family.add.library.to.classpath");
            }

            @Override
            public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
              return !project.isDisposed() && !currentModule.isDisposed() && libraryEntry.isValid();
            }

            @Override
            public void invoke(@NotNull final Project project, @Nullable final Editor editor, PsiFile file) {
              OrderEntryUtil.addLibraryToRoots(libraryEntry, currentModule);
              if (editor != null) {
                DumbService.getInstance(project).withAlternativeResolveEnabled(new Runnable() {
                  @Override
                  public void run() {
                    new AddImportAction(project, reference, editor, aClass).execute();
                  }
                });
              }
            }
          };

          final OrderEntryFix providedFix = provideFix(new Function<MissingDependencyFixProvider, OrderEntryFix>() {
            @Override
            public OrderEntryFix fun(MissingDependencyFixProvider provider) {
              return provider.getAddLibraryToClasspathFix(reference, platformFix, currentModule, libraryEntry, aClass);
            }
          });
          final OrderEntryFix fix = ObjectUtils.notNull(providedFix, platformFix);
          registrar.register(fix);
          result.add(fix);
        }
      }
    }
    return result;
  }

  private static List<PsiClass> filterAllowedDependencies(PsiElement element, PsiClass[] classes) {
    DependencyValidationManager dependencyValidationManager = DependencyValidationManager.getInstance(element.getProject());
    PsiFile fromFile = element.getContainingFile();
    List<PsiClass> result = new ArrayList<PsiClass>();
    for (PsiClass psiClass : classes) {
      if (dependencyValidationManager.getViolatorDependencyRule(fromFile, psiClass.getContainingFile()) == null) {
        result.add(psiClass);
      }
    }
    return result;
  }

  private static ThreeState isReferenceToAnnotation(final PsiElement psiElement) {
    if (!PsiUtil.isLanguageLevel5OrHigher(psiElement)) {
      return ThreeState.NO;
    }
    if (PsiTreeUtil.getParentOfType(psiElement, PsiAnnotation.class) != null) {
      return ThreeState.YES;
    }
    if (PsiTreeUtil.getParentOfType(psiElement, PsiImportStatement.class) != null) {
      return ThreeState.UNSURE;
    }
    return ThreeState.NO;
  }

  public static void addJarsToRootsAndImportClass(@NotNull List<String> jarPaths,
                                                  final String libraryName,
                                                  @NotNull final Module currentModule, @Nullable final Editor editor,
                                                  @Nullable final PsiReference reference,
                                                  @Nullable @NonNls final String className) {
    addJarsToRoots(jarPaths, libraryName, currentModule, reference != null ? reference.getElement() : null);

    final Project project = currentModule.getProject();
    if (editor != null && reference != null && className != null) {
      DumbService.getInstance(project).withAlternativeResolveEnabled(new Runnable() {
        @Override
        public void run() {
          GlobalSearchScope scope = GlobalSearchScope.moduleWithLibrariesScope(currentModule);
          PsiClass aClass = JavaPsiFacade.getInstance(project).findClass(className, scope);
          if (aClass != null) {
            new AddImportAction(project, reference, editor, aClass).execute();
          }
        }
      });
    }
  }

  public static void addJarToRoots(@NotNull String jarPath, final @NotNull Module module, @Nullable PsiElement location) {
    addJarsToRoots(Collections.singletonList(jarPath), null, module, location);
  }

  public static void addJarsToRoots(@NotNull final List<String> jarPaths, @Nullable final String libraryName,
                                    @NotNull final Module module, @Nullable final PsiElement location) {
    final Boolean isAdded = provideFix(new Function<MissingDependencyFixProvider, Boolean>() {
      @Override
      public Boolean fun(MissingDependencyFixProvider provider) {
        return provider.addJarsToRoots(jarPaths, libraryName, module, location);
      }
    });
    if (Boolean.TRUE.equals(isAdded)) return;

    List<String> urls = ContainerUtil.map(jarPaths, new Function<String, String>() {
      @Override
      public String fun(String path) {
        return refreshAndConvertToUrl(path);
      }
    });
    boolean inTests = false;
    if (location != null) {
      final VirtualFile vFile = location.getContainingFile().getVirtualFile();
      if (vFile != null && ModuleRootManager.getInstance(module).getFileIndex().isInTestSourceContent(vFile)) {
        inTests = true;
      }
    }
    ModuleRootModificationUtil.addModuleLibrary(module, libraryName, urls, Collections.<String>emptyList(),
                                                inTests ? DependencyScope.TEST : DependencyScope.COMPILE);
  }

  @NotNull
  private static String refreshAndConvertToUrl(String jarPath) {
    final File libraryRoot = new File(jarPath);
    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(libraryRoot);
    return VfsUtil.getUrlForLibraryRoot(libraryRoot);
  }

  @Nullable
  public static String locateAnnotationsJar(@NotNull Module module) {
    String jarName;
    String libPath;
    if (EffectiveLanguageLevelUtil.getEffectiveLanguageLevel(module).isAtLeast(LanguageLevel.JDK_1_8)) {
      jarName = "annotations-java8.jar";
      libPath = new File(PathManager.getHomePath(), "redist").getAbsolutePath();
    }
    else {
      jarName = "annotations.jar";
      libPath = PathManager.getLibPath();
    }
    final LocateLibraryDialog dialog = new LocateLibraryDialog(module, libPath, jarName, QuickFixBundle.message("add.library.annotations.description"));
    return dialog.showAndGet() ? dialog.getResultingLibraryPath() : null;
  }

  public static boolean isAnnotationsJarInPath(Module module) {
    if (module == null) return false;
    return JavaPsiFacade.getInstance(module.getProject())
             .findClass(AnnotationUtil.LANGUAGE, GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)) != null;
  }
}
