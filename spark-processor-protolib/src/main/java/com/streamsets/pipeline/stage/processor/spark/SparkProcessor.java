/**
 * Copyright 2016 StreamSets Inc.
 * <p>
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.processor.spark;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.base.SingleLaneProcessor;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.spark.api.SparkTransformer;
import com.streamsets.pipeline.spark.api.TransformResult;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.File;
import java.io.FilenameFilter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.streamsets.pipeline.stage.processor.spark.Errors.SPARK_00;
import static com.streamsets.pipeline.stage.processor.spark.Errors.SPARK_01;
import static com.streamsets.pipeline.stage.processor.spark.Errors.SPARK_02;
import static com.streamsets.pipeline.stage.processor.spark.Errors.SPARK_03;
import static com.streamsets.pipeline.stage.processor.spark.Errors.SPARK_04;
import static com.streamsets.pipeline.stage.processor.spark.Errors.SPARK_05;
import static com.streamsets.pipeline.stage.processor.spark.Errors.SPARK_06;
import static com.streamsets.pipeline.stage.processor.spark.Errors.SPARK_07;
import static com.streamsets.pipeline.stage.processor.spark.Groups.SPARK;

public class SparkProcessor extends SingleLaneProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SparkProcessor.class);
  public static final String TRANSFORMER_CLASS = "sparkProcessorConfigBean.transformerClass";

  private final SparkProcessorConfigBean configBean;
  private transient SparkTransformer transformer; // NOSONAR
  private transient JavaSparkContext jsc; // NOSONAR

  private ErrorRecordHandler errorRecordHandler = null;
  private boolean transformerInited = false;

  public SparkProcessor(SparkProcessorConfigBean configBean) {
    this.configBean = configBean;
  }

  @Override
  public List<ConfigIssue> init() {
    // We keep moving forward and adding more issues, so we can return as many validation issues in one shot
    List<ConfigIssue> issues = super.init();
    errorRecordHandler = new DefaultErrorRecordHandler(getContext());
    final Class<? extends SparkTransformer> transformerClazz = getTransformerClass(issues);
    transformer = createTransformer(issues, transformerClazz);
    final File[] jars = getJarFiles(issues);
    if (issues.isEmpty()) {
      jsc = startSparkContext(jars);
    }
    initTransformer(issues);
    if (!issues.isEmpty() && jsc != null) {
      jsc.stop();
    }
    return issues;
  }

  private JavaSparkContext startSparkContext(File[] jars) {
    SparkConf sparkConf = new SparkConf();
    sparkConf.setMaster(Utils.format("local[{}]", configBean.threadCount))
        .setAppName(configBean.appName)
        .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .set("spark.ui.enabled", "false")
        .set("spark.driver.userClassPathFirst", "true")
        .set("spark.executor.userClassPathFirst", "true")
        .set("spark.io.compression.codec", "lz4");
    JavaSparkContext sparkContext = new JavaSparkContext(sparkConf);
    if (jars != null) {
      for (File jar : jars) {
        LOG.info(Utils.format("Added {} to Spark Context", jar.getName()));
        sparkContext.addJar(jar.toString());
      }
    }
    return sparkContext;
  }

  private File[] getJarFiles(List<ConfigIssue> issues) {
    File[] jars = new File[0];
    try {
      if (issues.isEmpty()) {
        Path current = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParentFile().toPath();
        File container = current.getParent().getParent().getParent().resolve("container-lib").toFile();
        jars = container.listFiles(new FilenameFilter() {
          @Override
          public boolean accept(File dir, String name) {
            // Only RecordImpl is required for serialization/deserialization
            return name.matches("streamsets-datacollector-container-\\d(.*)\\.jar");
          }
        });
      }
    } catch (URISyntaxException ex) {
      LOG.error("Cannot find Streamsets directories", ex);
      issues.add(getContext().createConfigIssue(SPARK.name(), "appName", SPARK_03));
    }
    return jars;
  }

  private SparkTransformer createTransformer(List<ConfigIssue> issues, Class<? extends SparkTransformer> transformerClazz) {
    try {
      if (transformerClazz != null) {
        return transformerClazz.newInstance();
      }
    } catch (Exception ex) {
      LOG.error("Error while creating transformer", ex);
      issues.add(
          getContext().createConfigIssue(SPARK.name(), TRANSFORMER_CLASS, SPARK_02, configBean.transformerClass, getExceptionString(ex)));
    }
    return null;
  }

  @VisibleForTesting
  static String getExceptionString(Exception ex) {
    return ex.getMessage() == null ?
        ex.getClass().getCanonicalName() :
        ex.getClass().getCanonicalName() + " : " + ex.getMessage();
  }

  @SuppressWarnings("unchecked")
  private Class<? extends SparkTransformer> getTransformerClass(List<ConfigIssue> issues) {
    Class transformerClazz = null;
    try {
      transformerClazz = Class.forName(configBean.transformerClass);
      if (!SparkTransformer.class.isAssignableFrom(transformerClazz)) {
        issues.add(
            getContext().createConfigIssue(SPARK.name(), TRANSFORMER_CLASS, SPARK_00, configBean.transformerClass));
        return null;
      }
    } catch (ClassNotFoundException ex) {
      LOG.error(Utils.format("Cannot find class '{}' in classpath", configBean.transformerClass), ex);
      issues.add(
          getContext().createConfigIssue(SPARK.name(), TRANSFORMER_CLASS, SPARK_01, configBean.transformerClass));
    }
    return (Class<? extends SparkTransformer>) transformerClazz;
  }

  private void initTransformer(List<ConfigIssue> issues) {
    if (issues.isEmpty()) {
      try {
        transformer.init(jsc,
            configBean.preprocessMethodArgs == null ? new ArrayList<String>() : configBean.preprocessMethodArgs);
      } catch (Exception ex) {
        LOG.error("Error while initializing transformer class", ex);
        issues.add(getContext().createConfigIssue(SPARK.name(), TRANSFORMER_CLASS, SPARK_05, configBean.transformerClass, getExceptionString(ex)));
      }
      // Even if init failed, we should call destroy to ensure that the transformer cleans up after itself.
      transformerInited = true;
    }
  }

  @Override
  public void process(Batch batch, SingleLaneBatchMaker singleLaneBatchMaker) throws StageException {
    JavaRDD<Record> recordRDD = jsc.parallelize(ImmutableList.copyOf(batch.getRecords()), configBean.threadCount);

    TransformResult processed;
    try {
      processed = transformer.transform(recordRDD);
    } catch (Exception ex) {
      LOG.error("Error while transforming batch", ex);
      throw new StageException(SPARK_06, ex.getMessage());
    }
    JavaRDD<Record> results = processed.getResult();
    try {
      if (results != null) {
        for (Record out : results.collect()) {
          singleLaneBatchMaker.addRecord(clone(out));
        }
      }
    } catch (Exception ex) {
      LOG.error("Spark job failed", ex);
      throw new StageException(SPARK_07, ex.getMessage());
    }

    JavaPairRDD<Record, String> errors = processed.getErrors();
    try {
      if (errors != null) {
        for (Tuple2<Record, String> error : errors.collect()) {
          errorRecordHandler.onError(new OnRecordErrorException(clone(error._1()), SPARK_04, error._2()));
        }
      }
    } catch (Exception ex) {
      LOG.error("Spark job failed", ex);
      throw new StageException(SPARK_07, ex.getMessage());
    }
  }

  /**
   * Kryo loads the RecordImpl in Spark's classloader. So this one clones it to this stage's classloader.
   * @param record
   * @return
   */
  private Record clone(Record record) {
    // Kryo loads the RecordImpl class during deserialization in a Spark's classloader.
    // So directly using the deserialized RecordImpl gives a ClassCastException (RecordImpl -> RecordImpl).
    // So create a new record and set its root field to be the deserialized one's root field.
    Record r = getContext().createRecord(record);
    r.set(record.get());
    r.getHeader().setAllAttributes(record.getHeader().getAllAttributes());
    return r;
  }

  @Override
  public void destroy() {
    if (transformer != null && transformerInited) {
      try {
        transformer.destroy();
      } catch (Exception ex) {
        LOG.warn("Transformer threw exception during destroy", ex);
      }
    }
    if (jsc != null) {
      jsc.stop();
    }
  }
}
