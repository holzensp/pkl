/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.core.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.pkl.core.*;
import org.pkl.core.module.ModuleKeyFactories;
import org.pkl.core.resource.ResourceReaders;
import org.pkl.core.runtime.VmEvalException;
import org.pkl.core.util.IoUtils;
import org.pkl.core.util.Nullable;

/**
 * Java representation of a Pkl settings file. A Pkl settings file is a Pkl module amending the
 * {@literal pkl.settings} standard library module. To load a settings file, use one of the static
 * {@code load} methods.
 */
// keep in sync with stdlib/settings.pkl
public final class PklSettings {
  private static final List<Pattern> ALLOWED_MODULES =
      List.of(Pattern.compile("pkl:"), Pattern.compile("file:"));

  private static final List<Pattern> ALLOWED_RESOURCES =
      List.of(Pattern.compile("env:"), Pattern.compile("file:"));

  private final Editor editor;

  public PklSettings(Editor editor) {
    this.editor = editor;
  }

  /**
   * Loads the user settings file ({@literal ~/.pkl/settings.pkl}). If this file does not exist,
   * returns default settings defined by module {@literal pkl.settings}.
   */
  public static PklSettings loadFromPklHomeDir() throws VmEvalException {
    return loadFromPklHomeDir(IoUtils.getPklHomeDir());
  }

  /** For testing only. */
  static PklSettings loadFromPklHomeDir(Path pklHomeDir) throws VmEvalException {
    var path = pklHomeDir.resolve("settings.pkl");
    return Files.exists(path) ? load(ModuleSource.path(path)) : new PklSettings(Editor.SYSTEM);
  }

  /** Loads a settings file from the given path. */
  public static PklSettings load(ModuleSource moduleSource) throws VmEvalException {
    try (var evaluator =
        EvaluatorBuilder.unconfigured()
            .setSecurityManager(
                SecurityManagers.standard(
                    ALLOWED_MODULES, ALLOWED_RESOURCES, SecurityManagers.defaultTrustLevels, null))
            .setStackFrameTransformer(StackFrameTransformers.defaultTransformer)
            .addModuleKeyFactory(ModuleKeyFactories.standardLibrary)
            .addModuleKeyFactory(ModuleKeyFactories.file)
            .addResourceReader(ResourceReaders.environmentVariable())
            .addEnvironmentVariables(System.getenv())
            .build()) {
      var module = evaluator.evaluateOutputValueAs(moduleSource, PClassInfo.Settings);
      return parseSettings(module);
    }
  }

  private static PklSettings parseSettings(PObject module) throws VmEvalException {
    // can't use object mapping in pkl-core, so map manually
    var editor = (PObject) module.getProperty("editor");
    var urlScheme = (String) editor.getProperty("urlScheme");
    return new PklSettings(new Editor(urlScheme));
  }

  /** Returns the editor for viewing and editing Pkl files. */
  public Editor getEditor() {
    return editor;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    var that = (PklSettings) o;

    return editor.equals(that.editor);
  }

  @Override
  public int hashCode() {
    return editor.hashCode();
  }

  @Override
  public String toString() {
    return "PklSettings{" + "editor=" + editor + '}';
  }

  /** An editor for viewing and editing Pkl files. */
  public static final class Editor {
    private final String urlScheme;

    /** The editor associated with {@code file:} URLs ending in {@code .pkl}. */
    public static final Editor SYSTEM = new Editor("%{url}, line %{line}");

    /** The <a href="https://www.jetbrains.com/idea">IntelliJ IDEA</a> editor. */
    public static final Editor IDEA = new Editor("idea://open?file=%{path}&line=%{line}");

    /** The <a href="https://macromates.com">TextMate</a> editor. */
    public static final Editor TEXT_MATE =
        new Editor("txmt://open?url=%{url}&line=%{line}&column=%{column}");

    /** The <a href="https://www.sublimetext.com">Sublime Text</a> editor. */
    public static final Editor SUBLIME =
        new Editor("subl://open?url=%{url}&line=%{line}&column=%{column}");

    /** The <a href="https://atom.io">Atom</a> editor. */
    public static final Editor ATOM =
        new Editor("atom://open?url=%{url}&line=%{line}&column=%{column}");

    /** The <a href="https://code.visualstudio.com">Visual Studio Code</a> editor. */
    public static final Editor VS_CODE = new Editor("vscode://file/%{path}:%{line}:%{column}");

    /** Constructs an editor. */
    public Editor(String urlScheme) {
      this.urlScheme = urlScheme;
    }

    /**
     * Returns the URL scheme for opening files in this editor. The following placeholders are
     * supported: {@code %{url}}, {@code %{path}}, {@code %{line}}, {@code %{column}}.
     */
    public String getUrlScheme() {
      return urlScheme;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      var editor = (Editor) o;

      return urlScheme.equals(editor.urlScheme);
    }

    @Override
    public int hashCode() {
      return urlScheme.hashCode();
    }

    @Override
    public String toString() {
      return "Editor{" + "urlScheme='" + urlScheme + '\'' + '}';
    }
  }

  public static class Evaluator {
    private final @Nullable Map<String, String> externalProperties;
    private final @Nullable Map<String, String> env;
    private final @Nullable List<Pattern> allowedModules;
    private final @Nullable List<Pattern> allowedResources;
    private final @Nullable Boolean noCache;
    private final @Nullable Path moduleCacheDir;
    private final @Nullable List<Path> modulePath;
    private final @Nullable Duration timeout;
    private final @Nullable Path rootDir;

    public Evaluator(
      @Nullable Map<String, String> externalProperties,
      @Nullable Map<String, String> env,
      @Nullable List<Pattern> allowedModules,
      @Nullable List<Pattern> allowedResources,
      @Nullable Boolean noCache,
      @Nullable Path moduleCacheDir,
      @Nullable List<Path> modulePath,
      @Nullable Duration timeout,
      @Nullable Path rootDir) {
      this.externalProperties = externalProperties;
      this.env = env;
      this.allowedModules = allowedModules;
      this.allowedResources = allowedResources;
      this.noCache = noCache;
      this.moduleCacheDir = moduleCacheDir;
      this.modulePath = modulePath;
      this.timeout = timeout;
      this.rootDir = rootDir;
    }

    public @Nullable Map<String, String> getExternalProperties() {
      return externalProperties;
    }

    public @Nullable Map<String, String> getEnv() {
      return env;
    }

    public @Nullable List<Pattern> getAllowedModules() {
      return allowedModules;
    }

    public @Nullable List<Pattern> getAllowedResources() {
      return allowedResources;
    }

    public @Nullable Boolean isNoCache() {
      return noCache;
    }

    public @Nullable List<Path> getModulePath() {
      return modulePath;
    }

    public @Nullable Duration getTimeout() {
      return timeout;
    }

    public @Nullable Path getModuleCacheDir() {
      return moduleCacheDir;
    }

    public @Nullable Path getRootDir() {
      return rootDir;
    }

    private boolean arePatternsEqual(
      @Nullable List<Pattern> myPattern, @Nullable List<Pattern> thatPattern) {
      if (myPattern == null) {
        return thatPattern == null;
      }
      if (thatPattern == null) {
        return false;
      }
      if (myPattern.size() != thatPattern.size()) {
        return false;
      }
      for (var i = 0; i < myPattern.size(); i++) {
        if (!myPattern.get(i).pattern().equals(thatPattern.get(i).pattern())) {
          return false;
        }
      }
      return true;
    }

    private int hashPatterns(@Nullable List<Pattern> patterns) {
      if (patterns == null) {
        return 0;
      }
      var ret = 1;
      for (var pattern : patterns) {
        ret = 31 * ret + pattern.pattern().hashCode();
      }
      return ret;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Evaluator that = (Evaluator) o;
      return Objects.equals(externalProperties, that.externalProperties)
        && Objects.equals(env, that.env)
        && arePatternsEqual(allowedModules, that.allowedModules)
        && arePatternsEqual(allowedResources, that.allowedResources)
        && Objects.equals(noCache, that.noCache)
        && Objects.equals(moduleCacheDir, that.moduleCacheDir)
        && Objects.equals(modulePath, that.modulePath)
        && Objects.equals(timeout, that.timeout)
        && Objects.equals(rootDir, that.rootDir);
    }

    @Override
    public int hashCode() {
      var result =
        Objects.hash(
          externalProperties, env, noCache, moduleCacheDir, modulePath, timeout, rootDir);
      result = 31 * result + hashPatterns(allowedModules);
      result = 31 * result + hashPatterns(allowedResources);
      return result;
    }

    @Override
    public String toString() {
      return "EvaluatorSettings{"
        + "externalProperties="
        + externalProperties
        + ", env="
        + env
        + ", allowedModules="
        + allowedModules
        + ", allowedResources="
        + allowedResources
        + ", noCache="
        + noCache
        + ", moduleCacheDir="
        + moduleCacheDir
        + ", modulePath="
        + modulePath
        + ", timeout="
        + timeout
        + ", rootDir="
        + rootDir
        + '}';
    }
  }
}
