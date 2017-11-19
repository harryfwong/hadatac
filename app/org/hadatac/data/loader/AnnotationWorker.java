package org.hadatac.data.loader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.String;
import java.net.URL;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.jena.query.DatasetAccessor;
import org.apache.jena.query.DatasetAccessorFactory;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.hadatac.console.controllers.annotator.AnnotationLog;
import org.hadatac.console.http.ConfigUtils;
import org.hadatac.data.api.DataFactory;
import org.hadatac.data.loader.ccsv.Parser;
import org.hadatac.data.loader.util.Arguments;
import org.hadatac.data.loader.util.FileFactory;
import org.hadatac.data.model.ParsingResult;
import org.hadatac.entity.pojo.Credential;
import org.hadatac.entity.pojo.DASVirtualObject;
import org.hadatac.entity.pojo.DataAcquisition;
import org.hadatac.entity.pojo.DataFile;
import org.hadatac.metadata.loader.LabkeyDataHandler;
import org.hadatac.metadata.loader.ValueCellProcessing;
import org.labkey.remoteapi.CommandException;
import org.hadatac.utils.Collections;
import org.hadatac.utils.ConfigProp;
import org.hadatac.utils.Feedback;
import org.hadatac.utils.State;

import java.lang.Exception;

public class AnnotationWorker {
	public static Map<String, String> codeMappings = new HashMap<String, String>();
	public static Map<String, String> AttrORobj = new HashMap<String, String>();
	public static Map<String, String> entityMappings = new HashMap<String, String>();
	public static Map<String, List<String>> codebook = new HashMap<String, List<String>>();
	public static Map<String, Map<String,String>> codebook_K = new HashMap<String, Map<String,String>>();
	public static Map<String, List<DASVirtualObject>> templateLibrary = new HashMap<String, List<DASVirtualObject>>();
	public static String study_id = "default-study";
	public static final String kbPrefix = ConfigUtils.getKbPrefix();
	
	public AnnotationWorker() {}
    
	public static void autoAnnotate() {
		if(ConfigProp.getPropertyValue("autoccsv.config", "auto").equals("off")){
			return;
		}

		String path_proc = ConfigProp.getPropertyValue("autoccsv.config", "path_proc");
		String path_unproc = ConfigProp.getPropertyValue("autoccsv.config", "path_unproc");
		List<DataFile> unproc_files = DataFile.findAll(State.UNPROCESSED);
		DataFile.filterNonexistedFiles(path_unproc, unproc_files);

		for (DataFile file : unproc_files) {
			String file_name = file.getFileName();
			AnnotationLog log = new AnnotationLog(file_name);
			log.addline(Feedback.println(Feedback.WEB, String.format("[OK] Processing file: %s", file_name)));
			log.save();
			boolean bSucceed = false;
			if (file_name.startsWith("DA")) {
				bSucceed = annotateDAFile(file);
			}
			else if (file_name.startsWith("SID")) {
				bSucceed = annotateSampleIdFile(new File(path_unproc + "/" + file_name));
			}
			else if (file_name.startsWith("PID")) {
				bSucceed = annotateSubjectIdFile(new File(path_unproc + "/" + file_name));
			}
			else if (file_name.startsWith("STD")) {
				bSucceed = annotateStudyIdFile(new File(path_unproc + "/" + file_name));
			}
			else if (file_name.startsWith("MAP")) {
				bSucceed = annotateMapFile(new File(path_unproc + "/" + file_name));
			}
			else if (file_name.startsWith("ACQ")) {
				bSucceed = annotateACQFile(new File(path_unproc + "/" + file_name));
			}
			else if (file_name.startsWith("SDD")) {
				bSucceed = annotateDataAcquisitionSchemaFile(new File(path_unproc + "/" + file_name));
			}
			if (bSucceed) {
				//Move the file to the folder for processed files
				File destFolder = new File(path_proc);
				if (!destFolder.exists()){
					destFolder.mkdirs();
				}

				file.delete();

				file.setProcessStatus(true);
				file.setProcessTime(new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date()));
				file.save();
				File f = new File(path_unproc + "/" + file_name);
				f.renameTo(new File(destFolder + "/" + file_name));
				f.delete();
			}
		}
	}
	
	public static boolean annotateMapFile(File file) {
		boolean bSuccess = true;
		try {
			SampleSubjectMapper mapper = new SampleSubjectMapper(file);
			bSuccess = mapper.updateMappings();
		} catch (Exception e) {
			e.printStackTrace();
			AnnotationLog.printException(e, file.getName());
			return false;
		}

		return bSuccess;
	}

	public static boolean annotateStudyIdFile(File file) {
		boolean bSuccess = true;
		try {
			StudyGenerator studyGenerator = new StudyGenerator(file);
			bSuccess = commitRows(studyGenerator.createRows(), studyGenerator.toString(), 
					file.getName(), "Study", true);
		} catch (Exception e) {
			System.out.println("Error: annotateStudyIdFile() - Unable to generate study");
			AnnotationLog.printException(e, file.getName());
			return false;
		}
		try {
			SampleCollectionGenerator sampleCollectionGenerator = new SampleCollectionGenerator(file);
			bSuccess = commitRows(sampleCollectionGenerator.createRows(), sampleCollectionGenerator.toString(), 
					file.getName(), "SampleCollection", true);

		} catch (Exception e) {
			System.out.println("Error: annotateStudyIdFile() - Unable to generate Sample Collection");
			AnnotationLog.printException(e, file.getName());
			return false;
		}    	
		try {
			AgentGenerator agentGenerator = new AgentGenerator(file);
			bSuccess = commitRows(agentGenerator.createRows(), agentGenerator.toString(), 
					file.getName(), "Agent", true);

		} catch (Exception e) {
			System.out.println("Error: annotateStudyIdFile() - Unable to generate Agent");
			AnnotationLog.printException(e, file.getName());
			return false;
		}

		return bSuccess;
	}

	public static boolean annotateSampleIdFile(File file) {
		try {
			SampleGenerator sampleGenerator = new SampleGenerator(file);
			sampleGenerator.createRows();
		} catch (Exception e) {
			System.out.println("Error: annotateSampleIdFile() - Unable to generate Sample");
			AnnotationLog.printException(e, file.getName());
			return false;
		}

		return true;
	}

	public static boolean annotateSubjectIdFile(File file) {
		try {
			SubjectGenerator subjectGenerator = new SubjectGenerator(file);
			subjectGenerator.createRows();
		}
		catch (Exception e) {
			System.out.println("Error: annotateSubjectIdFile() - Unable to generate Subject");
			AnnotationLog.printException(e, file.getName());
			return false;
		}
		
		return true;
	}
	
	public static boolean annotateACQFile(File file) {
		boolean bSuccess = true;
		try {
			GeneralGenerator generalGenerator = new GeneralGenerator();
			Map<String, Object> row = new HashMap<String, Object>();
			row.put("hasURI", kbPrefix + "INS-GENERIC-PHYSICAL-INSTRUMENT");
			row.put("a", "vstoi:PhysicalInstrument");
			row.put("rdfs:label", "Generic Physical Instrument");
			generalGenerator.addRow(row);

			row = new HashMap<String, Object>();
			row.put("hasURI", kbPrefix + "INS-GENERIC-QUESTIONNAIRE");
			row.put("a", "hasco:Questionnaire");
			row.put("rdfs:label", "Generic Questionnaire");
			generalGenerator.addRow(row);
			bSuccess = commitRows(generalGenerator.getRows(), generalGenerator.toString(), file.getName(), 
					"Instrument", true);

			DateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
			String startTime = isoFormat.format(new Date());

			try{
				DeploymentGenerator deploymentGenerator = new DeploymentGenerator(file, startTime);
				bSuccess = commitRows(deploymentGenerator.createRows(), deploymentGenerator.toString(), file.getName(), 
						"Deployment", true);
			} catch (Exception e){
				System.out.println("Error in annotateDataAcquisitionFile: Deployment Generator");
				AnnotationLog.printException(e, file.getName());
			}
			try{
				DataAcquisitionGenerator daGenerator = new DataAcquisitionGenerator(file, startTime);
				bSuccess = commitRows(daGenerator.createRows(), daGenerator.toString(), file.getName(), 
						"DataAcquisition", true);
			} catch (Exception e){
				System.out.println("Error in annotateDataAcquisitionFile: Data Acquisition Generator");
				AnnotationLog.printException(e, file.getName());
			}
		} catch (Exception e) {
			System.out.println("Error in annotateDataAcquisitionFile");
			AnnotationLog.printException(e, file.getName());
			return false;
		}
		
		return bSuccess;
	}

	public static boolean annotateDataAcquisitionSchemaFile(File file) {
		System.out.println("Processing data acquisition schema file ...");
		
		boolean bSuccess = true;
		try{
			HashMap<String, String> hm = new HashMap<String, String>();

			if(file.getName().endsWith(".csv")) {
				try {
					BufferedReader bufRdr;
					bufRdr = new BufferedReader(new FileReader(file));
					String line = null;
					while((line = bufRdr.readLine()) != null) {
						try {
							hm.put(line.split(",")[0], line.split(",")[1]);
						} catch (Exception e) {
							hm.put(line.split(",")[0], "");
						}
					}
					bufRdr.close();
				} catch (Exception e) {
					e.printStackTrace();
					System.out.println("Error annotateDataAcquisitionSchemaFile: Unable to Read File");
				}

			} else if (file.getName().endsWith(".xlsx")){
				InputStream inp = new FileInputStream(file);
				Workbook wb = WorkbookFactory.create(inp);
				Sheet sheet = wb.getSheetAt(0);
				// Decide which rows to process
				int rowStart = Math.min(1, sheet.getFirstRowNum());
				int rowEnd = Math.min(7, sheet.getLastRowNum());

				for (int rowNum = rowStart; rowNum < rowEnd; rowNum++) {
					Row r = sheet.getRow(rowNum);
					if (r == null) {
						// This whole row is empty
						// Handle it as needed
						continue;
					} else {
						hm.put(r.getCell(0).getStringCellValue(), r.getCell(1).getStringCellValue());
					}
				}
			}
			
			if (hm.containsKey("Study_ID")){
				study_id = hm.get("Study_ID");
			}
			URL url = new URL(hm.get("Data_Dictionary"));
			//System.out.println(url.toString());
			File dd = new File("sddtmp/" + file.getName());
			//System.out.println(dd.getAbsoluteFile());
			FileUtils.copyURLToFile(url, dd);
			
			BufferedReader bufRdr = new BufferedReader(new FileReader(dd));
			String line = null;
			
			while((line = bufRdr.readLine()) != null){
				String str[] = line.split(",");
				AttrORobj.put(str[0], str[2]);
			}
			System.out.println("AttrORobj: " + AttrORobj);
			bufRdr.close();

			if (hm.get("Code_Mappings") != ""){
				URL url2 = new URL(hm.get("Code_Mappings"));
				//System.out.println(url2.toString());
				File cm = new File("sddtmp/" + file.getName().replace(".csv", "")+"-code-mappings.csv");
				//System.out.println(cm.getAbsoluteFile());
				FileUtils.copyURLToFile(url2, cm);
				BufferedReader bufRdr2 = new BufferedReader(new FileReader(cm));
				String line2 =  null;

				while((line2 = bufRdr2.readLine()) != null){
					String str[] = line2.split(",");
					codeMappings.put(str[0], str[1]);
				}
				bufRdr2.close();
				cm.delete();
			}

			try{
				URL url3 = new URL(hm.get("Codebook"));
				System.out.println(url3.toString());
				File codeBookFile = new File("sddtmp/" + file.getName().replace(".csv", "")+"-codebook.csv");
				FileUtils.copyURLToFile(url3, codeBookFile);
				BufferedReader bufRdr3 = new BufferedReader(new FileReader(codeBookFile));
				String line3 =  null;
				System.out.println("Read Codebook");
				
				Map<String, String> tempMap;
				String workingCol = "";
				String workingKey = "";
				// Read first line
				bufRdr3.readLine();
				while((line3 = bufRdr3.readLine()) != null){
					String[] codes = line3.split(",");
					List<String> codesl = Arrays.asList(codes); 
					codebook.put(codesl.get(0), codesl);
					
					// For Katie's purpose
					String tempCol = codesl.get(0);
					if(tempCol.equals("") || tempCol == null || codebook_K.containsKey(workingKey)){
						tempMap = codebook_K.get(workingKey);
						//System.out.println("[AutoAnnotator] before " + tempMap);
						tempMap.put(codesl.get(1), codesl.get(4));
						//System.out.println("[AutoAnnotator] after " + tempMap);
						codebook_K.put(workingKey, tempMap);
						//System.out.println("[AutoAnnotator] added to " + workingKey + ": " + codebook.get(workingKey));
						//System.out.println("[AutoAnnotator] size now " + codebook.get(workingKey).size());
					} else {
						workingCol = tempCol;
						workingKey = kbPrefix + "DASO-" + file.getName().replace("SDD-","").replace(".csv","") 
								+ "-" + workingCol.trim().replace(" ","").replace("_","-").replace("??", "");
						tempMap = new HashMap<String,String>();
						tempMap.put(codesl.get(1), codesl.get(4));
						codebook_K.put(workingKey, tempMap);
						//System.out.println("[AutoAnnotator]: added to " + workingKey + ": " + codebook.get(workingKey));
						//System.out.println("[AutoAnnotator]: size now " + codebook.get(workingKey).size());
					}
				}
				bufRdr3.close();

				PVGenerator pvGenerator = new PVGenerator(codeBookFile, file.getName(), study_id,
						codeMappings, codebook, AttrORobj);
				System.out.println("Calling PVGenerator");
				bSuccess = commitRows(pvGenerator.createRows(), pvGenerator.toString(), 
						file.getName(), "PossibleValue", true);
				codeBookFile.delete();
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("Error annotateDataAcquisitionSchemaFile: Unable to read codebook");
				File codeBookFile = new File(file.getName().replace(".csv", "")+"-codebook.csv");
				System.out.println(codeBookFile.getAbsoluteFile());
				System.out.println(codeBookFile.length());
				codeBookFile.delete();
				return false;
			}

			try {
				if (hm.containsKey("Study_ID")){
					study_id = hm.get("Study_ID");
				}
				
				try {
					DASchemaAttrGenerator dasaGenerator = new DASchemaAttrGenerator(dd, codeMappings);
					System.out.println("Calling DASchemaAttrGenerator");
					bSuccess = commitRows(dasaGenerator.createRows(), dasaGenerator.toString(), 
							file.getName(), "DASchemaAttribute", true);
				} catch (Exception e) {
					System.out.println("Error annotateDataAcquisitionSchemaFile: Unable to generate DASA.");
					AnnotationLog.printException(e, file.getName());
					return false;
				}
				
				try {
					DASchemaObjectGenerator dasoGenerator = new DASchemaObjectGenerator(dd, codeMappings);
					System.out.println("Calling DASchemaObjectGenerator");
					bSuccess = commitRows(dasoGenerator.createRows(), dasoGenerator.toString(), 
							file.getName(), "DASchemaObject", true);
					
					String SDDUri = ValueCellProcessing.replacePrefixEx(kbPrefix + "DAS-" + dasoGenerator.getSDDName());
					templateLibrary.put(SDDUri, dasoGenerator.getTemplateList());
					System.out.println("[AutoAnnotator]: adding templates for SDD " + SDDUri);
				} catch (Exception e) {
					System.out.println("Error annotateDataAcquisitionSchemaFile: Unable to generate DASO.");
					AnnotationLog.printException(e, file.getName());
					return false;
				}
				
				try {
					DASchemaEventGenerator daseGenerator = new DASchemaEventGenerator(dd, codeMappings);
					System.out.println("Calling DASchemaEventGenerator");
					bSuccess = commitRows(daseGenerator.createRows(), daseGenerator.toString(), 
							file.getName(), "DASchemaEvent", true);
				} catch (Exception e) {
					System.out.println("Error annotateDataAcquisitionSchemaFile: Unable to generate DASE.");
					AnnotationLog.printException(e, file.getName());
				}
				
				dd.delete();

				try {
					GeneralGenerator generalGenerator = new GeneralGenerator();
					System.out.println("Calling DASchemaGenerator");
					Map<String, Object> row = new HashMap<String, Object>();
					row.put("hasURI", kbPrefix + "DAS-" + file.getName().replace("SDD-","").replace(".csv",""));
					row.put("a", "hasco:DASchema");
					row.put("rdfs:label", "Schema for " + file.getName().replace("SDD-","").replace(".csv",""));
					row.put("rdfs:comment", "");
					generalGenerator.addRow(row);

					bSuccess = commitRows(generalGenerator.getRows(), generalGenerator.toString(), 
							file.getName(), "DASchema", true);	        	
				} catch (Exception e) {
					System.out.println("Error annotateDataAcquisitionSchemaFile: GeneralGenerator failed.");
					AnnotationLog.printException(e, file.getName());
					return false;
				}
			} catch (Exception e) {
				System.out.println("Error annotateDataAcquisitionSchemaFile: Unable to complete generation.");
				AnnotationLog.printException(e, file.getName());
				dd.delete();
				return false;
			}
		} catch (Exception e) {
			System.out.println("Error annotateDataAcquisitionSchemaFile: Annotation failed.");
			AnnotationLog.printException(e, file.getName());
			return false;
		}

		return bSuccess;
	}
	
	public static boolean annotateDAFile(DataFile dataFile) {
		System.out.println("annotateDAFile: [" + dataFile.getFileName() + "]");
		
		String file_name = dataFile.getFileName();    	
		AnnotationLog log = new AnnotationLog();
		log.setFileName(file_name);

		ArrayList<DASVirtualObject> templateList = new ArrayList<DASVirtualObject>();
		DataAcquisition da = null;
		String da_uri = null;
		String deployment_uri = null;
		String schema_uri = null;

		if (dataFile != null) {
			da = DataAcquisition.findByUri(ValueCellProcessing.replacePrefixEx(dataFile.getDataAcquisitionUri()));
			if (da != null) {
				if (!da.isComplete()) {
					log.addline(Feedback.println(Feedback.WEB, 
							String.format("[WARNING] Specification of associated Data Acquisition is incomplete: %s", file_name)));
					log.save();
					return false;
				} else {
					log.addline(Feedback.println(Feedback.WEB, String.format("[OK] Specification of associated Data Acquisition is complete: %s", file_name)));
				}
				da_uri = da.getUri();
				deployment_uri = da.getDeploymentUri();
				schema_uri = da.getSchemaUri();
			}
		}

		if (da_uri == null) {
			log.addline(Feedback.println(Feedback.WEB, String.format("[ERROR] Cannot find target data acquisition: %s", file_name)));
			log.save();
			return false;
		} else {
			log.addline(Feedback.println(Feedback.WEB, String.format("[OK] Found target data acquisition: %s", file_name)));
		}
		if (schema_uri == null) {
			log.addline(Feedback.println(Feedback.WEB, String.format("[ERROR] Cannot load schema specified for data acquisition: %s", file_name)));
			log.save();
			return false;
		} else {
			log.addline(Feedback.println(Feedback.WEB, String.format("[OK] Schema %s specified for data acquisition: %s", schema_uri, file_name)));
		}
		if (deployment_uri == null) {
			log.addline(Feedback.println(Feedback.WEB, String.format("[ERROR] Cannot load deployment specified for data acquisition: %s", file_name)));
			log.save();
			return false;
		} else {
			try {
				deployment_uri = URLDecoder.decode(deployment_uri, "UTF-8");
			} catch (UnsupportedEncodingException e) {
				log.addline(Feedback.println(Feedback.WEB, String.format("URL decoding error for deployment uri %s", deployment_uri)));
				log.save();
				return false;
			}
			log.addline(Feedback.println(Feedback.WEB, String.format("[OK] Deployment %s specified for data acquisition %s", deployment_uri, file_name)));
		}

		String path_unproc = ConfigProp.getPropertyValue("autoccsv.config", "path_unproc");
		int status = -1;
		String message = "";
		Arguments arguments = new Arguments();
		arguments.setInputPath(path_unproc + file_name);
		arguments.setInputType("CSV");
		arguments.setOutputPath("upload/");
		arguments.setVerbose(true);
		arguments.setPv(false);

		Parser parser = new Parser();
		ParsingResult result_parse;
		File inputFile = new File(arguments.getInputPath());
		FileFactory files = new FileFactory(arguments);
		files.setCSVFile(inputFile, inputFile.getName());

		try {
			files.openFile("log", "w");
			files.writeln("log", "[START] " + arguments.getInputPath() + " generating measurements.");
		} catch (Exception e) {
			log.addline(Feedback.println(Feedback.WEB, String.format("[ERROR] opening CSV file %s", e.getMessage())));
			log.save();
			return false;
		}

		try {
			if (arguments.getInputType().equals("CSV")) {
				System.out.println("annotateCSVFile: file to be parsed [" + dataFile.getFileName() + "]"); 
				dataFile.setDatasetUri(DataFactory.getNextDatasetURI(da.getUri()));
				da.addDatasetUri(dataFile.getDatasetUri());
				result_parse = parser.indexMeasurements(files, da, dataFile);
				status = result_parse.getStatus();
				message += result_parse.getMessage();
			}
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			log.addline(Feedback.println(Feedback.WEB, String.format("[ERROR] parsing and indexing CSV file %s", errors.toString())));
			log.save();
			return false;
		}

		if (status == 0) {
			return true;
		}

		return false;
	}
	
	public static Model createModel(List<Map<String, Object>> rows) {
		Model model = ModelFactory.createDefaultModel();
		for (Map<String, Object> row : rows) {
			Resource sub = model.createResource(ValueCellProcessing.replacePrefixEx((String)row.get("hasURI")));
			for (String key : row.keySet()) {
				if (!key.equals("hasURI")) {
					Property pred = null;
					if (key.equals("a")) {
						pred = model.createProperty(ValueCellProcessing.replacePrefixEx("rdf:type"));
					}
					else {
						pred = model.createProperty(ValueCellProcessing.replacePrefixEx(key));
					}

					String cellValue = (String)row.get(key);
					if (ValueCellProcessing.isAbbreviatedURI(cellValue)) {
						Resource obj = model.createResource(ValueCellProcessing.replacePrefixEx(cellValue));
						model.add(sub, pred, obj);
					}
					else {
						Literal obj = model.createLiteral(
								cellValue.replace("\n", " ").replace("\r", " ").replace("\"", "''"));
						model.add(sub, pred, obj);
					}
				}
			}
		}

		return model;
	}
	
	private static void checkRows(List<Map<String, Object>> rows, String primaryKey) throws Exception {
		int i = 1;
		Set<String> values = new HashSet<>();
		for (Map<String, Object> row : rows) {
			String val = (String)row.get(primaryKey);
			if (null == val) {
				throw new Exception(String.format("Found Row %d without URI specified!", i));
			}
			if (values.contains(val)) {
				throw new Exception(String.format("Duplicate Concepts in Inputfile row %d :" + val + " would be duplicate URIs!", i));
			}
			else {
				values.add(val);
			}

			i++;
		}
	}

	private static boolean commitRows(List<Map<String, Object>> rows, String contentInCSV,
			String fileName, String tableName, boolean toTripleStore) {

		AnnotationLog log = AnnotationLog.find(fileName);
		if (null == log) {
			log = new AnnotationLog();
			log.setFileName(fileName);
		}
		
		try {
			checkRows(rows, "hasURI");
			//System.out.println("checkRows succeed.");
		} catch (Exception e) {
			log.addline(Feedback.println(Feedback.WEB, String.format(
					"[ERROR] Trying to commit invalid rows to LabKey Table %s: ", tableName)
					+ e.getMessage()));
			log.save();
			return false;
		}

		Credential cred = Credential.find();
		if (null == cred) {
			log.resetLog();
			log.addline(Feedback.println(Feedback.WEB, "[ERROR] No LabKey credentials are provided!"));
			log.save();
			return false;
		}

		String site = ConfigProp.getPropertyValue("labkey.config", "site");
		String path = "/" + ConfigProp.getPropertyValue("labkey.config", "folder");
		LabkeyDataHandler labkeyDataHandler = new LabkeyDataHandler(
				site, cred.getUserName(), cred.getPassword(), path);
		try {
			//System.out.println(rows.size());
			log.addline(Feedback.println(Feedback.WEB, "The first Row is " + rows.get(0).toString()));
			int nRows = labkeyDataHandler.insertRows(tableName, rows);
			//System.out.println("insert rows succeed.");
			log.addline(Feedback.println(Feedback.WEB, String.format(
					"[OK] %d row(s) have been inserted into Table %s ", nRows, tableName)));
		} catch (CommandException e1) {
			try {
				labkeyDataHandler.deleteRows(tableName, rows);
				int nRows = labkeyDataHandler.insertRows(tableName, rows);
				//System.out.println("update " + nRows + " rows succeed in " + tableName + " .");
				log.addline(Feedback.println(Feedback.WEB, String.format("[OK] %d row(s) have been updated into Table %s ", nRows, tableName)));
			} catch (CommandException e) {
				log.addline(Feedback.println(Feedback.WEB, "[ERROR] CommitRows inside AutoAnnotator: " + e));
				log.save();
				return false;
			}
		}

		if (toTripleStore) {
			DatasetAccessor accessor = DatasetAccessorFactory.createHTTP(
					Collections.getCollectionsName(Collections.METADATA_GRAPH));
			Model model = createModel(rows);
			accessor.add(model); // check URI's
			log.addline(Feedback.println(Feedback.WEB, String.format("[OK] %d triple(s) have been committed to triple store", model.size())));
		}

		// THIS LINE IS EXCEEDING THE STORAGE CAPABILITY OF ONE CELL IN THE SOLR DATA COLLECTION FOR ANNOTATION LOG
		//log.addline(Feedback.println(Feedback.WEB, String.format(contentInCSV)));
		log.save();

		return true;
	}
}