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
package org.pkl.core.project;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.pkl.core.Composite;
import org.pkl.core.Duration;
import org.pkl.core.EvaluatorBuilder;
import org.pkl.core.ModuleSource;
import org.pkl.core.PClassInfo;
import org.pkl.core.PNull;
import org.pkl.core.PObject;
import org.pkl.core.PklException;
import org.pkl.core.SecurityManager;
import org.pkl.core.SecurityManagers;
import org.pkl.core.StackFrameTransformer;
import org.pkl.core.StackFrameTransformers;
import org.pkl.core.Version;
import org.pkl.core.module.ModuleKeyFactories;
import org.pkl.core.packages.Checksums;
import org.pkl.core.packages.Dependency.RemoteDependency;
import org.pkl.core.packages.PackageUri;
import org.pkl.core.packages.PackageUtils;
import org.pkl.core.resource.ResourceReaders;
import org.pkl.core.settings.PklSettings;
import org.pkl.core.util.Nullable;

/** Java representation of module {@code pkl.Project}. */
public final class Project {
  private final @Nullable Package pkg;
  private final DeclaredDependencies dependencies;
  private final PklSettings.Evaluator evaluatorSettings;
  private final URI projectFileUri;
  private final Path projectDir;
  private final List<Path> tests;
  private final Map<String, Project> localProjectDependencies;

  /**
   * Loads Project data from the given {@link Path}.
   *
   * <p>Evaluates a module's {@code output.value} to allow for embedding a project within a
   * template.
   *
   * @throws PklException if an error occurred while evaluating the project file.
   */
  public static Project loadFromPath(
      Path path,
      SecurityManager securityManager,
      @Nullable java.time.Duration timeout,
      StackFrameTransformer stackFrameTransformer,
      Map<String, String> envVars) {
    try (var evaluator =
        EvaluatorBuilder.unconfigured()
            .setSecurityManager(securityManager)
            .setStackFrameTransformer(stackFrameTransformer)
            .addModuleKeyFactory(ModuleKeyFactories.standardLibrary)
            .addModuleKeyFactory(ModuleKeyFactories.file)
            .addResourceReader(ResourceReaders.environmentVariable())
            .addResourceReader(ResourceReaders.file())
            .addEnvironmentVariables(envVars)
            .setTimeout(timeout)
            .build()) {
      var output = evaluator.evaluateOutputValueAs(ModuleSource.path(path), PClassInfo.Project);
      return Project.parseProject(output);
    } catch (URISyntaxException e) {
      throw new PklException(e.getMessage(), e);
    }
  }

  /** Convenience method to load a project with the default stack frame transformer. */
  public static Project loadFromPath(
      Path path, SecurityManager securityManager, @Nullable java.time.Duration timeout) {
    return loadFromPath(
        path, securityManager, timeout, StackFrameTransformers.defaultTransformer, System.getenv());
  }

  /**
   * Convenience method to load a project with the default security manager, no timeout, and the
   * default stack frame transformer.
   */
  public static Project loadFromPath(Path path) {
    return loadFromPath(path, SecurityManagers.defaultManager, null);
  }

  private static DeclaredDependencies parseDependencies(
      PObject module, URI projectFileUri, @Nullable PackageUri packageUri)
      throws URISyntaxException {
    var remoteDependencies = new HashMap<String, RemoteDependency>();
    var localDependencies = new HashMap<String, DeclaredDependencies>();
    //noinspection unchecked
    var dependencies = (Map<String, PObject>) module.getProperty("dependencies");
    for (var entry : dependencies.entrySet()) {
      var value = entry.getValue();
      if (value.getClassInfo().equals(PClassInfo.Project)) {
        var localProjectFileUri = URI.create((String) value.getProperty("projectFileUri"));
        var localPkgUri =
            PackageUri.create((String) ((PObject) value.getProperty("package")).getProperty("uri"));
        localDependencies.put(
            entry.getKey(), parseDependencies(value, localProjectFileUri, localPkgUri));
      } else {
        remoteDependencies.put(entry.getKey(), parseRemoteDependency(value));
      }
    }
    return new DeclaredDependencies(
        remoteDependencies, localDependencies, projectFileUri, packageUri);
  }

  private static RemoteDependency parseRemoteDependency(PObject object) throws URISyntaxException {
    var packageUri = new PackageUri((String) object.getProperty("uri"));
    PackageUtils.checkHasNoChecksumComponent(packageUri);
    var objChecksum = object.getProperty("checksums");
    Checksums checksums = null;
    if (objChecksum instanceof PObject pObject) {
      var sha256 = (String) pObject.get("sha256");
      assert sha256 != null;
      checksums = new Checksums(sha256);
    }
    return new RemoteDependency(packageUri, checksums);
  }

  public static Project parseProject(PObject module) throws URISyntaxException {
    var pkgObj = getNullableProperty(module, "package");
    var projectFileUri = URI.create((String) module.getProperty("projectFileUri"));
    var dependencies = parseDependencies(module, projectFileUri, null);
    var projectDir = Path.of(projectFileUri).getParent();
    Package pkg = null;
    if (pkgObj != null) {
      pkg = parsePackage((PObject) pkgObj);
    }
    var evaluatorSettings =
        getProperty(
            module,
            "evaluatorSettings",
            (settings) -> parseEvaluatorSettings(settings, projectDir));
    @SuppressWarnings("unchecked")
    var testPathStrs = (List<String>) getProperty(module, "tests");
    var tests =
        testPathStrs.stream()
            .map((it) -> projectDir.resolve(it).normalize())
            .collect(Collectors.toList());
    var localProjectDependencies = parseLocalProjectDependencies(module);
    return new Project(
        pkg,
        dependencies,
        evaluatorSettings,
        projectFileUri,
        projectDir,
        tests,
        localProjectDependencies);
  }

  private static Map<String, Project> parseLocalProjectDependencies(PObject module)
      throws URISyntaxException {
    //noinspection unchecked
    var dependencies = (Map<String, PObject>) module.getProperty("dependencies");
    var result = new HashMap<String, Project>();
    for (var entry : dependencies.entrySet()) {
      var value = entry.getValue();
      if (value.getClassInfo().equals(PClassInfo.Project)) {
        result.put(entry.getKey(), parseProject(entry.getValue()));
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static PklSettings.Evaluator parseEvaluatorSettings(Object settings, Path projectDir) {
    var pSettings = (PObject) settings;
    var externalProperties = getNullableProperty(pSettings, "externalProperties", Project::asMap);
    var env = getNullableProperty(pSettings, "env", Project::asMap);
    var allowedModules = getNullableProperty(pSettings, "allowedModules", Project::asPatternList);
    var allowedResources =
        getNullableProperty(pSettings, "allowedResources", Project::asPatternList);
    var noCache = (Boolean) getNullableProperty(pSettings, "noCache");
    var modulePathStrs = (List<String>) getNullableProperty(pSettings, "modulePath");
    List<Path> modulePath = null;
    if (modulePathStrs != null) {
      modulePath =
          modulePathStrs.stream()
              .map((it) -> projectDir.resolve(it).normalize())
              .collect(Collectors.toList());
    }
    var timeout = (Duration) getNullableProperty(pSettings, "timeout");
    var moduleCacheDir = getNullablePath(pSettings, "moduleCacheDir", projectDir);
    var rootDir = getNullablePath(pSettings, "rootDir", projectDir);
    return new PklSettings.Evaluator(
        externalProperties,
        env,
        allowedModules,
        allowedResources,
        noCache,
        moduleCacheDir,
        modulePath,
        timeout,
        rootDir);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> asMap(Object t) {
    assert t instanceof Map;
    return (Map<String, String>) t;
  }

  @SuppressWarnings("unchecked")
  private static List<Pattern> asPatternList(Object t) {
    return ((List<String>) t).stream().map(Pattern::compile).collect(Collectors.toList());
  }

  private static Object getProperty(PObject settings, String propertyName) {
    return settings.getProperty(propertyName);
  }

  private static <T> T getProperty(PObject settings, String propertyName, Function<Object, T> f) {
    return Objects.requireNonNull(getNullableProperty(settings, propertyName, f));
  }

  private static @Nullable Object getNullableProperty(Composite object, String propertyName) {
    var result = object.getPropertyOrNull(propertyName);
    if (result instanceof PNull || result == null) {
      return null;
    }
    return result;
  }

  private static @Nullable <T> T getNullableProperty(
      Composite object, String propertyName, Function<Object, T> f) {
    var value = object.getPropertyOrNull(propertyName);
    if (value instanceof PNull || value == null) {
      return null;
    }
    return f.apply(value);
  }

  private static @Nullable URI getNullableURI(Composite object, String propertyName)
      throws URISyntaxException {
    var value = object.getPropertyOrNull(propertyName);
    if (value instanceof PNull || value == null) {
      return null;
    }
    return new URI((String) value);
  }

  private static @Nullable Path getNullablePath(
      Composite object, String propertyName, Path projectDir) {
    return getNullableProperty(
        object, propertyName, (obj) -> projectDir.resolve((String) obj).normalize());
  }

  @SuppressWarnings("unchecked")
  private static Package parsePackage(PObject pObj) throws URISyntaxException {
    var name = (String) pObj.getProperty("name");
    var uri = new PackageUri((String) pObj.getProperty("uri"));
    var version = Version.parse((String) getProperty(pObj, "version"));
    var packageZipUrl = new URI((String) getProperty(pObj, "packageZipUrl"));
    var description = (String) getNullableProperty(pObj, "description");
    var authors = (List<String>) getProperty(pObj, "authors");
    var website = getNullableURI(pObj, "website");
    var documentation = getNullableURI(pObj, "documentation");
    var sourceCode = getNullableURI(pObj, "sourceCode");
    var sourceCodeUrlScheme = (String) getNullableProperty(pObj, "sourceCodeUrlScheme");
    var license = (String) getNullableProperty(pObj, "license");
    var licenseText = (String) getNullableProperty(pObj, "licenseText");
    var issueTracker = (URI) getNullableURI(pObj, "issueTracker");
    var apiTestStrs = (List<String>) getProperty(pObj, "apiTests");
    var apiTests = apiTestStrs.stream().map(Path::of).collect(Collectors.toList());
    var exclude = (List<String>) getProperty(pObj, "exclude");

    return new Package(
        name,
        uri,
        version,
        packageZipUrl,
        description,
        authors,
        website,
        documentation,
        sourceCode,
        sourceCodeUrlScheme,
        license,
        licenseText,
        issueTracker,
        apiTests,
        exclude);
  }

  private Project(
      @Nullable Package pkg,
      DeclaredDependencies dependencies,
      PklSettings.Evaluator evaluatorSettings,
      URI projectFileUri,
      Path projectDir,
      List<Path> tests,
      Map<String, Project> localProjectDependencies) {
    this.pkg = pkg;
    this.dependencies = dependencies;
    this.evaluatorSettings = evaluatorSettings;
    this.projectFileUri = projectFileUri;
    this.projectDir = projectDir;
    this.tests = tests;
    this.localProjectDependencies = localProjectDependencies;
  }

  public @Nullable Package getPackage() {
    return pkg;
  }

  public PklSettings.Evaluator getSettings() {
    return evaluatorSettings;
  }

  public URI getProjectFileUri() {
    return projectFileUri;
  }

  public List<Path> getTests() {
    return tests;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Project project = (Project) o;
    return Objects.equals(pkg, project.pkg)
        && dependencies.equals(project.dependencies)
        && evaluatorSettings.equals(project.evaluatorSettings)
        && projectFileUri.equals(project.projectFileUri)
        && tests.equals(project.tests);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pkg, dependencies, evaluatorSettings, projectFileUri, tests);
  }

  public DeclaredDependencies getDependencies() {
    return dependencies;
  }

  public Map<String, Project> getLocalProjectDependencies() {
    return localProjectDependencies;
  }

  public Path getProjectDir() {
    return projectDir;
  }
}
