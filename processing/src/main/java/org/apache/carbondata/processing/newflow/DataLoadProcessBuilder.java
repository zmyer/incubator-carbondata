/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.carbondata.processing.newflow;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.carbondata.common.logging.LogService;
import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.carbon.AbsoluteTableIdentifier;
import org.apache.carbondata.core.carbon.metadata.schema.table.CarbonTable;
import org.apache.carbondata.core.carbon.metadata.schema.table.column.CarbonColumn;
import org.apache.carbondata.core.carbon.metadata.schema.table.column.CarbonDimension;
import org.apache.carbondata.core.carbon.metadata.schema.table.column.CarbonMeasure;
import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datastorage.store.filesystem.CarbonFile;
import org.apache.carbondata.core.util.CarbonProperties;
import org.apache.carbondata.processing.model.CarbonLoadModel;
import org.apache.carbondata.processing.newflow.constants.DataLoadProcessorConstants;
import org.apache.carbondata.processing.newflow.exception.CarbonDataLoadingException;
import org.apache.carbondata.processing.newflow.steps.DataConverterProcessorStepImpl;
import org.apache.carbondata.processing.newflow.steps.DataWriterProcessorStepImpl;
import org.apache.carbondata.processing.newflow.steps.InputProcessorStepImpl;
import org.apache.carbondata.processing.newflow.steps.SortProcessorStepImpl;
import org.apache.carbondata.processing.util.CarbonDataProcessorUtil;

/**
 * It builds the pipe line of steps for loading data to carbon.
 */
public final class DataLoadProcessBuilder {

  private static final LogService LOGGER =
      LogServiceFactory.getLogService(DataLoadProcessBuilder.class.getName());

  public AbstractDataLoadProcessorStep build(CarbonLoadModel loadModel, String storeLocation,
      Iterator[] inputIterators) throws Exception {
    CarbonDataLoadConfiguration configuration =
        createConfiguration(loadModel, storeLocation);
    // 1. Reads the data input iterators and parses the data.
    AbstractDataLoadProcessorStep inputProcessorStep =
        new InputProcessorStepImpl(configuration, inputIterators);
    // 2. Converts the data like dictionary or non dictionary or complex objects depends on
    // data types and configurations.
    AbstractDataLoadProcessorStep converterProcessorStep =
        new DataConverterProcessorStepImpl(configuration, inputProcessorStep);
    // 3. Sorts the data which are part of key (all dimensions except complex types)
    AbstractDataLoadProcessorStep sortProcessorStep =
        new SortProcessorStepImpl(configuration, converterProcessorStep);
    // 4. Writes the sorted data in carbondata format.
    AbstractDataLoadProcessorStep writerProcessorStep =
        new DataWriterProcessorStepImpl(configuration, sortProcessorStep);
    return writerProcessorStep;
  }

  private CarbonDataLoadConfiguration createConfiguration(CarbonLoadModel loadModel,
      String storeLocation) throws Exception {
    if (!new File(storeLocation).mkdirs()) {
      LOGGER.error("Error while creating the temp store path: " + storeLocation);
    }
    CarbonDataLoadConfiguration configuration = new CarbonDataLoadConfiguration();
    String databaseName = loadModel.getDatabaseName();
    String tableName = loadModel.getTableName();
    String tempLocationKey = databaseName + CarbonCommonConstants.UNDERSCORE + tableName
        + CarbonCommonConstants.UNDERSCORE + loadModel.getTaskNo();
    CarbonProperties.getInstance().addProperty(tempLocationKey, storeLocation);
    CarbonProperties.getInstance()
        .addProperty(CarbonCommonConstants.STORE_LOCATION_HDFS, loadModel.getStorePath());

    CarbonTable carbonTable = loadModel.getCarbonDataLoadSchema().getCarbonTable();
    AbsoluteTableIdentifier identifier = carbonTable.getAbsoluteTableIdentifier();
    configuration.setTableIdentifier(identifier);
    String csvHeader = loadModel.getCsvHeader();
    String csvFileName = null;
    if (csvHeader != null && !csvHeader.isEmpty()) {
      configuration.setHeader(CarbonDataProcessorUtil.getColumnFields(csvHeader, ","));
    } else {
      CarbonFile csvFile =
          CarbonDataProcessorUtil.getCsvFileToRead(loadModel.getFactFilesToProcess().get(0));
      csvFileName = csvFile.getName();
      csvHeader = CarbonDataProcessorUtil.getFileHeader(csvFile);
      configuration.setHeader(
          CarbonDataProcessorUtil.getColumnFields(csvHeader, loadModel.getCsvDelimiter()));
    }
    if (!CarbonDataProcessorUtil
        .isHeaderValid(loadModel.getTableName(), csvHeader, loadModel.getCarbonDataLoadSchema(),
            loadModel.getCsvDelimiter())) {
      if (csvFileName == null) {
        LOGGER.error("CSV header provided in DDL is not proper."
            + " Column names in schema and CSV header are not the same.");
        throw new CarbonDataLoadingException(
            "CSV header provided in DDL is not proper. Column names in schema and CSV header are "
                + "not the same.");
      } else {
        LOGGER.error(
            "CSV File provided is not proper. Column names in schema and csv header are not same. "
                + "CSVFile Name : " + csvFileName);
        throw new CarbonDataLoadingException(
            "CSV File provided is not proper. Column names in schema and csv header are not same. "
                + "CSVFile Name : " + csvFileName);
      }
    }

    configuration.setPartitionId(loadModel.getPartitionId());
    configuration.setSegmentId(loadModel.getSegmentId());
    configuration.setTaskNo(loadModel.getTaskNo());
    configuration.setDataLoadProperty(DataLoadProcessorConstants.COMPLEX_DELIMITERS,
        new String[] { loadModel.getComplexDelimiterLevel1(),
            loadModel.getComplexDelimiterLevel2() });
    configuration.setDataLoadProperty(DataLoadProcessorConstants.SERIALIZATION_NULL_FORMAT,
        loadModel.getSerializationNullFormat().split(",")[1]);
    configuration.setDataLoadProperty(DataLoadProcessorConstants.FACT_TIME_STAMP,
        loadModel.getFactTimeStamp());
    configuration.setDataLoadProperty(DataLoadProcessorConstants.BAD_RECORDS_LOGGER_ENABLE,
        loadModel.getBadRecordsLoggerEnable().split(",")[1]);
    configuration.setDataLoadProperty(DataLoadProcessorConstants.BAD_RECORDS_LOGGER_ACTION,
        loadModel.getBadRecordsAction().split(",")[1]);
    configuration.setDataLoadProperty(DataLoadProcessorConstants.FACT_FILE_PATH,
        loadModel.getFactFilePath());
    List<CarbonDimension> dimensions =
        carbonTable.getDimensionByTableName(carbonTable.getFactTableName());
    List<CarbonMeasure> measures =
        carbonTable.getMeasureByTableName(carbonTable.getFactTableName());
    Map<String, String> dateFormatMap =
        CarbonDataProcessorUtil.getDateFormatMap(loadModel.getDateFormat());
    List<DataField> dataFields = new ArrayList<>();
    List<DataField> complexDataFields = new ArrayList<>();

    // First add dictionary and non dictionary dimensions because these are part of mdk key.
    // And then add complex data types and measures.
    for (CarbonColumn column : dimensions) {
      DataField dataField = new DataField(column);
      dataField.setDateFormat(dateFormatMap.get(column.getColName()));
      if (column.isComplex()) {
        complexDataFields.add(dataField);
      } else {
        dataFields.add(dataField);
      }
    }
    dataFields.addAll(complexDataFields);
    for (CarbonColumn column : measures) {
      // This dummy measure is added when no measure was present. We no need to load it.
      if (!(column.getColName().equals("default_dummy_measure"))) {
        dataFields.add(new DataField(column));
      }
    }
    configuration.setDataFields(dataFields.toArray(new DataField[dataFields.size()]));
    return configuration;
  }

}
