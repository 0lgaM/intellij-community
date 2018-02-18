// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.testDiscovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.annotations.SerializedName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.io.HttpRequests;
import com.intellij.util.io.RequestBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IntellijTestDiscoveryProducer implements TestDiscoveryProducer {
  private static final String INTELLIJ_TEST_DISCOVERY_HOST = "http://intellij-test-discovery";

  @NotNull
  @Override
  public List<DiscoveredTest> getDiscoveredTests(Project project, String classFQName, String methodName, String frameworkId) {
    String methodFqn = classFQName + "." + methodName;
    RequestBuilder r = HttpRequests.request(INTELLIJ_TEST_DISCOVERY_HOST + "/search/tests/by-method/" + methodFqn);

    try {
      return r.connect(request -> {
        List<DiscoveredTest> map = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        TestsSearchResult result = mapper.readValue(request.getInputStream(), TestsSearchResult.class);

        result.getTests().forEach(s -> {
          s = s.length() > 1 && s.charAt(0) == 'j' ? s.substring(1) : s;
          String classFqn = StringUtil.substringBefore(s, "-");
          String testMethodName = StringUtil.substringAfter(s, "-");
          map.add(new DiscoveredTest(classFqn, testMethodName));
        });
        return map;
      });
    }
    catch (HttpRequests.HttpStatusException http) {
      LOG.debug("No tests found for " + methodFqn);
    }
    catch (IOException e) {
      LOG.debug(e);
    }
    return Collections.emptyList();
  }

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public static class TestsSearchResult {
    @Nullable
    private String method;

    @SerializedName("class")
    @JsonProperty("class")
    @Nullable
    private String className;

    private int found;

    @NotNull
    private List<String> tests = new ArrayList<>();

    @Nullable
    private String message;

    @Nullable
    public String getMethod() {
      return method;
    }

    public TestsSearchResult setMethod(String method) {
      this.method = method;
      return this;
    }

    @Nullable
    public String getClassName() {
      return className;
    }

    public TestsSearchResult setClassName(String name) {
      this.className = name;
      return this;
    }

    public int getFound() {
      return found;
    }

    @NotNull
    public List<String> getTests() {
      return tests;
    }

    public TestsSearchResult setTests(List<String> tests) {
      this.tests = tests;
      this.found = tests.size();
      return this;
    }

    @Nullable
    public String getMessage() {
      return message;
    }

    public TestsSearchResult setMessage(String message) {
      this.message = message;
      return this;
    }
  }
}
