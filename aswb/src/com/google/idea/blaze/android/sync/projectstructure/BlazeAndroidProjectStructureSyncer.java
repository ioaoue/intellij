/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.sync.projectstructure;

import static java.util.stream.Collectors.toSet;

import com.android.annotations.VisibleForTesting;
import com.android.builder.model.SourceProvider;
import com.android.ide.common.gradle.model.SourceProviderUtil;
import com.android.ide.common.util.PathString;
import com.android.ide.common.util.PathStringUtil;
import com.android.projectmodel.AndroidPathType;
import com.android.projectmodel.SourceSet;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.idea.blaze.android.manifest.ManifestParser;
import com.google.idea.blaze.android.manifest.ParsedManifestService;
import com.google.idea.blaze.android.projectview.GeneratedAndroidResourcesSection;
import com.google.idea.blaze.android.resources.BlazeLightResourceClassService;
import com.google.idea.blaze.android.run.BlazeAndroidRunConfigurationHandler;
import com.google.idea.blaze.android.sync.importer.BlazeImportInput;
import com.google.idea.blaze.android.sync.importer.BlazeImportUtil;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.AndroidResourceModuleRegistry;
import com.google.idea.blaze.android.sync.model.AndroidSdkPlatform;
import com.google.idea.blaze.android.sync.model.BlazeAndroidSyncData;
import com.google.idea.blaze.android.sync.model.idea.BlazeAndroidModel;
import com.google.idea.blaze.android.sync.sdk.SdkUtil;
import com.google.idea.blaze.base.command.buildresult.OutputArtifactResolver;
import com.google.idea.blaze.base.ideinfo.AndroidIdeInfo;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.logging.EventLoggingService;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.projectview.section.sections.BuildFlagsSection;
import com.google.idea.blaze.base.projectview.section.sections.TargetSection;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin;
import com.google.idea.blaze.base.sync.data.BlazeDataStorage;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.projectstructure.ModuleEditorProvider;
import com.google.idea.blaze.base.sync.projectstructure.ModuleFinder;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.java.AndroidBlazeRules;
import com.google.idea.common.experiments.FeatureRolloutExperiment;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.jetbrains.android.facet.AndroidFacet;

/** Updates the IDE's project structure. */
public class BlazeAndroidProjectStructureSyncer {
  private static final Logger log = Logger.getInstance(BlazeAndroidProjectStructureSyncer.class);

  /**
   * True if run configuration should use the workspace module instead of per-run-configuration
   * modules.
   */
  @VisibleForTesting
  public static final FeatureRolloutExperiment deprecateRunConfigModuleExperiment =
      new FeatureRolloutExperiment("blaze.deprecate.run.config.modules");

  private static class ManifestParsingStatCollector {
    private Duration totalDuration = Duration.ZERO;
    private int fileCount = 0;

    /** Adds duration to total duration counter. Also increments file count. */
    void addDuration(Duration duration) {
      totalDuration = totalDuration.plus(duration);
      fileCount++;
    }

    /** Logs the total number of files processed and the amount of time it took. */
    void submitLogEvent() {
      EventLoggingService.getInstance()
          .logEvent(
              BlazeAndroidProjectStructureSyncer.class,
              "PostSyncManifestParsing",
              ImmutableMap.of(
                  "fileCount", "" + fileCount, "totalDurationMs", "" + totalDuration.toMillis()));
    }
  }

  public static void updateProjectStructure(
      Project project,
      BlazeContext context,
      ProjectViewSet projectViewSet,
      BlazeProjectData blazeProjectData,
      BlazeSyncPlugin.ModuleEditor moduleEditor,
      Module workspaceModule,
      ModifiableRootModel workspaceModifiableModel,
      boolean isAndroidWorkspace) {
    if (!isAndroidWorkspace) {
      AndroidFacetModuleCustomizer.removeAndroidFacet(workspaceModule);
      return;
    }

    BlazeAndroidSyncData syncData = blazeProjectData.getSyncState().get(BlazeAndroidSyncData.class);
    if (syncData == null) {
      return;
    }
    AndroidSdkPlatform androidSdkPlatform = syncData.androidSdkPlatform;
    if (androidSdkPlatform == null) {
      return;
    }

    // Configure workspace module as an android module
    AndroidFacetModuleCustomizer.createAndroidFacet(workspaceModule, false);

    // Create android resource modules
    // Because we're setting up dependencies, the modules have to exist before we configure them
    Map<TargetKey, AndroidResourceModule> targetToAndroidResourceModule = Maps.newHashMap();
    for (AndroidResourceModule androidResourceModule :
        syncData.importResult.androidResourceModules) {
      targetToAndroidResourceModule.put(androidResourceModule.targetKey, androidResourceModule);
      String moduleName = moduleNameForAndroidModule(androidResourceModule.targetKey);
      Module module = moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
      TargetIdeInfo target = blazeProjectData.getTargetMap().get(androidResourceModule.targetKey);
      AndroidFacetModuleCustomizer.createAndroidFacet(
          module,
          target != null
              && target.kindIsOneOf(
                  AndroidBlazeRules.RuleTypes.ANDROID_BINARY.getKind(),
                  AndroidBlazeRules.RuleTypes.ANDROID_TEST.getKind()));
    }

    // Configure android resource modules
    int totalOrderEntries = 0;
    Set<File> existingRoots = Sets.newHashSet();
    for (AndroidResourceModule androidResourceModule : targetToAndroidResourceModule.values()) {
      TargetIdeInfo target = blazeProjectData.getTargetMap().get(androidResourceModule.targetKey);
      AndroidIdeInfo androidIdeInfo = target.getAndroidIdeInfo();
      assert androidIdeInfo != null;

      String moduleName = moduleNameForAndroidModule(target.getKey());
      Module module = moduleEditor.findModule(moduleName);
      assert module != null;
      ModifiableRootModel modifiableRootModel = moduleEditor.editModule(module);
      LibraryTable libraryTable = ProjectLibraryTable.getInstance(project);
      ArtifactLocationDecoder artifactLocationDecoder =
          blazeProjectData.getArtifactLocationDecoder();
      File moduleDirectory =
          moduleDirectoryForAndroidTarget(WorkspaceRoot.fromProject(project), target);

      ArrayList<File> newRoots =
          new ArrayList<>(
              OutputArtifactResolver.resolveAll(
                  project, artifactLocationDecoder, androidResourceModule.resources));
      File manifest =
          manifestFileForAndroidTarget(
              project, artifactLocationDecoder, androidIdeInfo, moduleDirectory);
      if (manifest != null) {
        newRoots.add(manifest);
      }

      // Remove existing resource roots to silence the duplicate content root error.
      // We can only do this if we have cyclic resource dependencies, since otherwise we risk
      // breaking dependencies within this resource module.
      newRoots.removeAll(existingRoots);
      existingRoots.addAll(newRoots);
      ResourceModuleContentRootCustomizer.setupContentRoots(modifiableRootModel, newRoots);

      modifiableRootModel.addModuleOrderEntry(workspaceModule);
      ++totalOrderEntries;

      for (String libraryName : androidResourceModule.resourceLibraryKeys) {
        Library lib = libraryTable.getLibraryByName(libraryName);
        if (lib == null) {
          String message =
              String.format(
                  "Could not find library '%s' for module '%s'. Re-syncing might fix this issue.",
                  libraryName, moduleName);
          log.warn(message);
          context.output(PrintOutput.log(message));
        } else {
          modifiableRootModel.addLibraryEntry(lib);
        }
      }
      // Add a dependency from the workspace to the resource module
      ModuleOrderEntry orderEntry = workspaceModifiableModel.addModuleOrderEntry(module);
      ++totalOrderEntries;
      orderEntry.setExported(true);
    }

    String runConfigurationModuleCount;
    if (deprecateRunConfigModuleExperiment.isEnabled()) {
      runConfigurationModuleCount = "skipped";
    } else {
      List<TargetIdeInfo> runConfigurationTargets =
          getRunConfigurationTargets(
              project, projectViewSet, blazeProjectData, targetToAndroidResourceModule.keySet());
      for (TargetIdeInfo target : runConfigurationTargets) {
        TargetKey targetKey = target.getKey();
        String moduleName = moduleNameForAndroidModule(targetKey);
        Module module = moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
        AndroidFacetModuleCustomizer.createAndroidFacet(module, true);
      }
      runConfigurationModuleCount = "" + runConfigurationTargets.size();
    }

    int whitelistedGenResources =
        projectViewSet.listItems(GeneratedAndroidResourcesSection.KEY).size();
    context.output(
        PrintOutput.log(
            String.format(
                "Android resource module count: %d, run config modules: %s, order entries: %d, "
                    + "generated resources: %d",
                syncData.importResult.androidResourceModules.size(),
                runConfigurationModuleCount,
                totalOrderEntries,
                whitelistedGenResources)));
  }

  // Collect potential android run configuration targets
  private static List<TargetIdeInfo> getRunConfigurationTargets(
      Project project,
      ProjectViewSet projectViewSet,
      BlazeProjectData blazeProjectData,
      Set<TargetKey> androidResourceModules) {
    List<TargetIdeInfo> result = Lists.newArrayList();
    Set<Label> runConfigurationModuleTargets = Sets.newHashSet();

    // Get all explicitly mentioned targets
    // Doing this now will cut down on root changes later
    for (TargetExpression targetExpression : projectViewSet.listItems(TargetSection.KEY)) {
      if (!(targetExpression instanceof Label)) {
        continue;
      }
      Label label = (Label) targetExpression;
      runConfigurationModuleTargets.add(label);
    }
    // Get any pre-existing targets
    for (RunConfiguration runConfiguration :
        RunManager.getInstance(project).getAllConfigurationsList()) {
      BlazeAndroidRunConfigurationHandler handler =
          BlazeAndroidRunConfigurationHandler.getHandlerFrom(runConfiguration);
      if (handler == null) {
        continue;
      }
      runConfigurationModuleTargets.add(handler.getLabel());
    }

    for (Label label : runConfigurationModuleTargets) {
      TargetKey targetKey = TargetKey.forPlainTarget(label);
      // If it's a resource module, it will already have been created
      if (androidResourceModules.contains(targetKey)) {
        continue;
      }
      // Ensure the label is a supported android rule that exists
      TargetIdeInfo target = blazeProjectData.getTargetMap().get(targetKey);
      if (target == null) {
        continue;
      }
      if (!target.kindIsOneOf(
          AndroidBlazeRules.RuleTypes.ANDROID_BINARY.getKind(),
          AndroidBlazeRules.RuleTypes.ANDROID_TEST.getKind())) {
        continue;
      }
      result.add(target);
    }
    return result;
  }

  /** Ensures a suitable module exists for the given android target. */
  @Nullable
  public static Module ensureRunConfigurationModule(Project project, Label label) {
    if (deprecateRunConfigModuleExperiment.isEnabled()) {
      return ModuleFinder.getInstance(project)
          .findModuleByName(BlazeDataStorage.WORKSPACE_MODULE_NAME);
    }
    TargetKey targetKey = TargetKey.forPlainTarget(label);
    String moduleName = moduleNameForAndroidModule(targetKey);
    Module module = ModuleFinder.getInstance(project).findModuleByName(moduleName);
    if (module != null) {
      return module;
    }

    BlazeProjectData blazeProjectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (blazeProjectData == null) {
      return null;
    }
    AndroidSdkPlatform androidSdkPlatform = SdkUtil.getAndroidSdkPlatform(blazeProjectData);
    if (androidSdkPlatform == null) {
      return null;
    }
    TargetIdeInfo target = blazeProjectData.getTargetMap().get(targetKey);
    if (target == null) {
      return null;
    }
    if (target.getAndroidIdeInfo() == null) {
      return null;
    }
    // We can't run a write action outside the dispatch thread, and can't
    // invokeAndWait it because the caller may have a read action.
    if (!ApplicationManager.getApplication().isDispatchThread()) {
      return null;
    }

    BlazeSyncPlugin.ModuleEditor moduleEditor =
        ModuleEditorProvider.getInstance()
            .getModuleEditor(
                project, BlazeImportSettingsManager.getInstance(project).getImportSettings());
    Module newModule = moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
    ApplicationManager.getApplication()
        .runWriteAction(
            () -> {
              AndroidFacetModuleCustomizer.createAndroidFacet(newModule, true);
              moduleEditor.commit();
            });
    File moduleDirectory =
        moduleDirectoryForAndroidTarget(WorkspaceRoot.fromProject(project), target);
    updateModuleFacetInMemoryState(
        project,
        null,
        androidSdkPlatform,
        newModule,
        moduleDirectory,
        manifestFileForAndroidTarget(
            project,
            blazeProjectData.getArtifactLocationDecoder(),
            target.getAndroidIdeInfo(),
            moduleDirectory),
        target.getAndroidIdeInfo().getResourceJavaPackage(),
        ImmutableList.of(),
        false,
        null);
    return newModule;
  }

  public static String moduleNameForAndroidModule(TargetKey targetKey) {
    return targetKey
        .toString()
        .substring(2) // Skip initial "//"
        .replace('/', '.')
        .replace(':', '.');
  }

  public static void updateInMemoryState(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeProjectData blazeProjectData,
      Module workspaceModule,
      boolean isAndroidWorkspace) {
    BlazeLightResourceClassService.Builder rClassBuilder =
        new BlazeLightResourceClassService.Builder(project);
    AndroidResourceModuleRegistry registry = AndroidResourceModuleRegistry.getInstance(project);
    registry.clear();
    if (isAndroidWorkspace) {
      updateInMemoryState(
          project,
          context,
          workspaceRoot,
          projectViewSet,
          blazeProjectData,
          workspaceModule,
          registry,
          rClassBuilder);
    }
    BlazeLightResourceClassService.getInstance(project).installRClasses(rClassBuilder);
  }

  private static void updateInMemoryState(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeProjectData blazeProjectData,
      Module workspaceModule,
      AndroidResourceModuleRegistry registry,
      BlazeLightResourceClassService.Builder rClassBuilder) {
    BlazeAndroidSyncData syncData = blazeProjectData.getSyncState().get(BlazeAndroidSyncData.class);
    if (syncData == null) {
      return;
    }
    AndroidSdkPlatform androidSdkPlatform = syncData.androidSdkPlatform;
    if (androidSdkPlatform == null) {
      return;
    }
    ManifestParsingStatCollector manifestParsingStatCollector = new ManifestParsingStatCollector();
    boolean configAndroidJava8Libs = hasConfigAndroidJava8Libs(projectViewSet);

    updateWorkspaceModuleFacetInMemoryState(
        project,
        context,
        workspaceRoot,
        workspaceModule,
        androidSdkPlatform,
        configAndroidJava8Libs,
        manifestParsingStatCollector);

    ArtifactLocationDecoder artifactLocationDecoder = blazeProjectData.getArtifactLocationDecoder();
    ModuleFinder moduleFinder = ModuleFinder.getInstance(project);

    BlazeImportInput input =
        BlazeImportInput.forProject(project, workspaceRoot, projectViewSet, blazeProjectData);

    // Get package names from all visible targets.
    Set<String> sourcePackages =
        BlazeImportUtil.getSourceTargetsStream(input)
            .filter(targetIdeInfo -> targetIdeInfo.getAndroidIdeInfo() != null)
            .map(targetIdeInfo -> BlazeImportUtil.javaResourcePackageFor(targetIdeInfo, true))
            .collect(toSet());

    for (AndroidResourceModule androidResourceModule :
        syncData.importResult.androidResourceModules) {
      TargetIdeInfo target = blazeProjectData.getTargetMap().get(androidResourceModule.targetKey);
      String moduleName = moduleNameForAndroidModule(target.getKey());
      Module module = moduleFinder.findModuleByName(moduleName);
      if (module == null) {
        log.warn("No module found for resource target: " + target.getKey());
        continue;
      }
      registry.put(module, androidResourceModule);

      AndroidIdeInfo androidIdeInfo = target.getAndroidIdeInfo();
      assert androidIdeInfo != null;

      List<File> resources =
          OutputArtifactResolver.resolveAll(
              project, artifactLocationDecoder, androidResourceModule.resources);
      updateModuleFacetInMemoryState(
          project,
          context,
          androidSdkPlatform,
          module,
          moduleDirectoryForAndroidTarget(workspaceRoot, target),
          manifestFileForAndroidTarget(
              project,
              artifactLocationDecoder,
              androidIdeInfo,
              moduleDirectoryForAndroidTarget(workspaceRoot, target)),
          androidIdeInfo.getResourceJavaPackage(),
          resources,
          configAndroidJava8Libs,
          manifestParsingStatCollector);
      String modulePackage = androidIdeInfo.getResourceJavaPackage();
      rClassBuilder.addRClass(modulePackage, module);
      sourcePackages.remove(modulePackage);
    }

    rClassBuilder.addWorkspacePackages(sourcePackages);

    if (deprecateRunConfigModuleExperiment.isEnabled()) {
      manifestParsingStatCollector.submitLogEvent();
      return;
    }

    Set<TargetKey> androidResourceModules =
        syncData.importResult.androidResourceModules.stream()
            .map(androidResourceModule -> androidResourceModule.targetKey)
            .collect(toSet());
    List<TargetIdeInfo> runConfigurationTargets =
        getRunConfigurationTargets(
            project, projectViewSet, blazeProjectData, androidResourceModules);
    for (TargetIdeInfo target : runConfigurationTargets) {
      String moduleName = moduleNameForAndroidModule(target.getKey());
      Module module = moduleFinder.findModuleByName(moduleName);
      if (module == null) {
        log.warn("No module found for run configuration target: " + target.getKey());
        continue;
      }
      AndroidIdeInfo androidIdeInfo = target.getAndroidIdeInfo();
      assert androidIdeInfo != null;
      updateModuleFacetInMemoryState(
          project,
          context,
          androidSdkPlatform,
          module,
          moduleDirectoryForAndroidTarget(workspaceRoot, target),
          manifestFileForAndroidTarget(
              project,
              artifactLocationDecoder,
              androidIdeInfo,
              moduleDirectoryForAndroidTarget(workspaceRoot, target)),
          androidIdeInfo.getResourceJavaPackage(),
          ImmutableList.of(),
          configAndroidJava8Libs,
          manifestParsingStatCollector);
    }
    manifestParsingStatCollector.submitLogEvent();
  }

  @VisibleForTesting
  static boolean hasConfigAndroidJava8Libs(ProjectViewSet projectViewSet) {
    return projectViewSet.listItems(BuildFlagsSection.KEY).stream()
        .anyMatch(f -> "--config=android_java8_libs".equals(f));
  }

  private static File moduleDirectoryForAndroidTarget(
      WorkspaceRoot workspaceRoot, TargetIdeInfo target) {
    return workspaceRoot.fileForPath(target.getKey().getLabel().blazePackage());
  }

  @Nullable
  private static File manifestFileForAndroidTarget(
      Project project,
      ArtifactLocationDecoder artifactLocationDecoder,
      AndroidIdeInfo androidIdeInfo,
      File moduleDirectory) {
    ArtifactLocation manifestArtifactLocation = androidIdeInfo.getManifest();
    return manifestArtifactLocation != null
        ? OutputArtifactResolver.resolve(project, artifactLocationDecoder, manifestArtifactLocation)
        : new File(moduleDirectory, "AndroidManifest.xml");
  }

  /** Updates the shared workspace module with android info. */
  private static void updateWorkspaceModuleFacetInMemoryState(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      Module workspaceModule,
      AndroidSdkPlatform androidSdkPlatform,
      boolean configAndroidJava8Libs,
      @Nullable ManifestParsingStatCollector manifestParsingStatCollector) {
    File moduleDirectory = workspaceRoot.directory();
    String resourceJavaPackage = ":workspace";
    updateModuleFacetInMemoryState(
        project,
        context,
        androidSdkPlatform,
        workspaceModule,
        moduleDirectory,
        null,
        resourceJavaPackage,
        ImmutableList.of(),
        configAndroidJava8Libs,
        manifestParsingStatCollector);
  }

  private static void updateModuleFacetInMemoryState(
      Project project,
      @Nullable BlazeContext context,
      AndroidSdkPlatform androidSdkPlatform,
      Module module,
      File moduleDirectory,
      @Nullable File manifestFile,
      String resourceJavaPackage,
      Collection<File> resources,
      boolean configAndroidJava8Libs,
      @Nullable ManifestParsingStatCollector manifestParsingStatCollector) {
    List<PathString> manifests =
        manifestFile == null
            ? ImmutableList.of()
            : ImmutableList.of(PathStringUtil.toPathString(manifestFile));
    SourceSet sourceSet =
        new SourceSet(
            ImmutableMap.of(
                AndroidPathType.RES,
                PathStringUtil.toPathStrings(resources),
                AndroidPathType.MANIFEST,
                manifests));
    SourceProvider sourceProvider =
        SourceProviderUtil.toSourceProvider(sourceSet, module.getName());

    String applicationId =
        getApplicationIdFromManifestOrDefault(
            project, context, manifestFile, resourceJavaPackage, manifestParsingStatCollector);

    BlazeAndroidModel androidModel =
        new BlazeAndroidModel(
            project,
            moduleDirectory,
            sourceProvider,
            applicationId,
            androidSdkPlatform.androidMinSdkLevel,
            configAndroidJava8Libs);
    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet != null) {
      BlazeAndroidProjectStructureSyncerCompat.updateAndroidFacetWithSourceAndModel(
          facet, sourceProvider, androidModel);
    }
  }

  /**
   * Parses the provided manifest to calculate applicationId. Returns the provided default if the
   * manifest file does not exist, or is invalid
   */
  private static String getApplicationIdFromManifestOrDefault(
      Project project,
      @Nullable BlazeContext context,
      @Nullable File manifestFile,
      String defaultId,
      @Nullable ManifestParsingStatCollector manifestParsingStatCollector) {
    if (manifestFile == null) {
      return defaultId;
    }

    String applicationId = defaultId;
    try {
      Stopwatch timer = Stopwatch.createStarted();
      ManifestParser.ParsedManifest parsedManifest =
          ParsedManifestService.getInstance(project).getParsedManifest(manifestFile);
      if (manifestParsingStatCollector != null) {
        manifestParsingStatCollector.addDuration(timer.elapsed());
      }

      if (parsedManifest != null && parsedManifest.packageName != null) {
        applicationId = parsedManifest.packageName;
      } else {
        String message = "Could not parse manifest file: " + manifestFile;
        log.warn(message);
        if (context != null) {
          context.output(PrintOutput.log(message));
        }
      }
    } catch (IOException e) {
      String message = "Exception while reading manifest file: " + manifestFile;
      log.warn(message, e);
      if (context != null) {
        context.output(PrintOutput.log(message));
      }
    }
    return applicationId;
  }
}
