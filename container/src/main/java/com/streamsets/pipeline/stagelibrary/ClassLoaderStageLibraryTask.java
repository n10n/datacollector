/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stagelibrary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.api.ChooserMode;
import com.streamsets.pipeline.api.OnRecordError;
import com.streamsets.pipeline.api.OnRecordErrorChooserValues;
import com.streamsets.pipeline.json.ObjectMapperFactory;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.config.ConfigDefinition;
import com.streamsets.pipeline.config.ModelDefinition;
import com.streamsets.pipeline.config.ModelType;
import com.streamsets.pipeline.config.StageDefinition;
import com.streamsets.pipeline.api.impl.LocaleInContext;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.task.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class ClassLoaderStageLibraryTask extends AbstractTask implements StageLibraryTask {
  private static final Logger LOG = LoggerFactory.getLogger(ClassLoaderStageLibraryTask.class);

  private static final String PIPELINE_STAGES_JSON = "PipelineStages.json";

  private final RuntimeInfo runtimeInfo;
  private List<? extends ClassLoader> stageClassLoaders;
  private Map<String, StageDefinition> stageMap;
  private List<StageDefinition> stageList;
  private LoadingCache<Locale, List<StageDefinition>> localizedStageList;
  private ObjectMapper json;

  @Inject
  public ClassLoaderStageLibraryTask(RuntimeInfo runtimeInfo) {
    super("stageLibrary");
    this.runtimeInfo = runtimeInfo;
  }

  @Override
  protected void initTask() {
    stageClassLoaders = runtimeInfo.getStageLibraryClassLoaders();
    json = ObjectMapperFactory.get();
    stageList = new ArrayList<>();
    stageMap = new HashMap<>();
    loadStages();
    stageList = ImmutableList.copyOf(stageList);
    stageMap = ImmutableMap.copyOf(stageMap);

    localizedStageList = CacheBuilder.newBuilder().build(new CacheLoader<Locale, List<StageDefinition>>() {
      @Override
      public List<StageDefinition> load(Locale key) throws Exception {
        List<StageDefinition> list = new ArrayList<>();
        for (StageDefinition stage : stageList) {
          list.add(stage.localize());
        }
        return list;
      }
    });

  }

  @VisibleForTesting
  void loadStages() {
    if (LOG.isDebugEnabled()) {
      for (ClassLoader cl : stageClassLoaders) {
        LOG.debug("About to load stages from library '{}'", StageLibraryUtils.getLibraryName(cl));
      }
    }
    try {
      LocaleInContext.set(Locale.getDefault());
      for (ClassLoader cl : stageClassLoaders) {
        String libraryName = StageLibraryUtils.getLibraryName(cl);
        String libraryLabel = StageLibraryUtils.getLibraryLabel(cl);
        LOG.debug("Loading stages from library '{}'", libraryName);
        try {
          Enumeration<URL> resources = cl.getResources(PIPELINE_STAGES_JSON);
          while (resources.hasMoreElements()) {
            Map<String, String> stagesInLibrary = new HashMap<>();

            URL url = resources.nextElement();
            InputStream is = url.openStream();
            StageDefinition[] stages = json.readValue(is, StageDefinition[].class);
            for (StageDefinition stage : stages) {
              stage.setLibrary(libraryName, libraryLabel, cl);
              String key = createKey(libraryName, stage.getName(), stage.getVersion());
              LOG.debug("Loaded stage '{}' (library:name:version)", key);
              if (stagesInLibrary.containsKey(key)) {
                throw new IllegalStateException(Utils.format(
                    "Library '{}' contains more than one definition for stage '{}', class '{}' and class '{}'",
                    libraryName, key, stagesInLibrary.get(key), stage.getStageClass()));
              }
              addSystemConfigurations(stage);
              stagesInLibrary.put(key, stage.getClassName());
              stageList.add(stage);
              stageMap.put(key, stage);
            }
          }
        } catch (IOException ex) {
          throw new RuntimeException(
              Utils.format("Could not load stages definition from '{}', {}", cl, ex.getMessage()),
              ex);
        }
      }
    } finally {
      LocaleInContext.set(null);
    }
  }

  //Group name needs to be empty for UI to show the config in General Group.
  private static final ConfigDefinition REQUIRED_FIELDS_CONFIG = new ConfigDefinition(
      ConfigDefinition.REQUIRED_FIELDS, ConfigDef.Type.MODEL, "Required Fields",
      "Records without any of these fields are sent to error",
      null, false, "", null, new ModelDefinition(ModelType.FIELD_SELECTOR_MULTI_VALUED, null, null, null, null, null),
      "", new String[] {}, 10);

  //Group name needs to be empty for UI to show the config in General Group.
  private static final ConfigDefinition ON_RECORD_ERROR_CONFIG = new ConfigDefinition(
      ConfigDefinition.ON_RECORD_ERROR, ConfigDef.Type.MODEL, "On Record Error",
      "Action to take with records sent to error",
      OnRecordError.TO_ERROR.name(), true, "", null, new ModelDefinition(ModelType.VALUE_CHOOSER, ChooserMode.PROVIDED,
                                                 OnRecordErrorChooserValues.class.getName(),
                                                 new OnRecordErrorChooserValues().getValues(),
                                                 new OnRecordErrorChooserValues().getLabels(), null), "",
      new String[] {}, 20);

  private void addSystemConfigurations(StageDefinition stage) {
    if (stage.hasRequiredFields()) {
      stage.addConfiguration(REQUIRED_FIELDS_CONFIG);
    }
    if (stage.hasOnRecordError()) {
      stage.addConfiguration(ON_RECORD_ERROR_CONFIG);
    }
  }

  private String createKey(String library, String name, String version) {
    return library + ":" + name + ":" + version;
  }

  @Override
  public List<StageDefinition> getStages() {
    try {
      return (LocaleInContext.get() == null) ? stageList : localizedStageList.get(LocaleInContext.get());
    } catch (ExecutionException ex) {
      LOG.warn("Error loading locale '{}', {}", LocaleInContext.get(), ex.getMessage(), ex);
      return stageList;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public StageDefinition getStage(String library, String name, String version) {
    return stageMap.get(createKey(library, name, version));
  }

}
