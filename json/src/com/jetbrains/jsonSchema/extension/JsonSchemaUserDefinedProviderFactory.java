// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.jsonSchema.extension;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PairProcessor;
import com.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration;
import com.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration;
import com.jetbrains.jsonSchema.ide.JsonSchemaService;
import com.jetbrains.jsonSchema.remote.JsonFileResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.jetbrains.jsonSchema.JsonSchemaConfigurable.isHttpPath;

/**
 * @author Irina.Chernushina on 2/13/2016.
 */
public class JsonSchemaUserDefinedProviderFactory implements JsonSchemaProviderFactory {
  @NotNull
  @Override
  public List<JsonSchemaFileProvider> getProviders(@NotNull Project project) {
    final JsonSchemaMappingsProjectConfiguration configuration = JsonSchemaMappingsProjectConfiguration.getInstance(project);

    final Map<String, UserDefinedJsonSchemaConfiguration> map = configuration.getStateMap();
    final List<JsonSchemaFileProvider> providers = map.values().stream()
      .map(schema -> new MyProvider(project, schema.getName(), isHttpPath(schema.getRelativePathToSchema())
                                                               ? schema.getRelativePathToSchema()
                                                               : new File(project.getBasePath(), schema.getRelativePathToSchema()).getAbsolutePath(),
                                    schema.getCalculatedPatterns())).collect(Collectors.toList());

    return providers.isEmpty() ? Collections.emptyList() : providers;
  }

  static class MyProvider implements JsonSchemaFileProvider, JsonSchemaImportedProviderMarker {
    @NotNull private final Project myProject;
    @NotNull private final String myName;
    @NotNull private final String myFile;
    private VirtualFile myVirtualFile;
    @NotNull private final List<PairProcessor<Project, VirtualFile>> myPatterns;

    public MyProvider(@NotNull final Project project,
                      @NotNull final String name,
                      @NotNull final String file,
                      @NotNull final List<PairProcessor<Project, VirtualFile>> patterns) {
      myProject = project;
      myName = name;
      myFile = file;
      myPatterns = patterns;
    }

    @Nullable
    @Override
    public VirtualFile getSchemaFile() {
      if (myVirtualFile != null && myVirtualFile.isValid()) return myVirtualFile;
      String path = myFile;
      if (isHttpPath(path)) {
        myVirtualFile = JsonFileResolver.urlToFile(path);
      }
      else {
        final LocalFileSystem lfs = LocalFileSystem.getInstance();
        myVirtualFile = lfs.findFileByPath(myFile);
        if (myVirtualFile == null) {
          myVirtualFile = lfs.refreshAndFindFileByPath(myFile);
        }
      }
      return myVirtualFile;
    }

    @NotNull
    @Override
    public SchemaType getSchemaType() {
      return SchemaType.userSchema;
    }

    @NotNull
    @Override
    public String getName() {
      return myName;
    }

    @Override
    public boolean isAvailable(@NotNull VirtualFile file) {
      //noinspection SimplifiableIfStatement
      if (myPatterns.isEmpty() || file.isDirectory() || !file.isValid() || getSchemaFile() == null ||
          JsonSchemaService.Impl.get(myProject).isSchemaFile(file)) return false;
      return myPatterns.stream().anyMatch(processor -> processor.process(myProject, file));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      MyProvider provider = (MyProvider)o;

      if (!myName.equals(provider.myName)) return false;
      return FileUtil.pathsEqual(myFile, provider.myFile);
    }

    @Override
    public int hashCode() {
      int result = myName.hashCode();
      result = 31 * result + FileUtil.pathHashCode(myFile);
      return result;
    }
  }
}
