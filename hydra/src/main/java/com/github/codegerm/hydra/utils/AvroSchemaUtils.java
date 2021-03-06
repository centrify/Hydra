package com.github.codegerm.hydra.utils;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.avro.Schema;
import org.apache.flume.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.codegerm.hydra.source.SqlSourceUtil;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

public final class AvroSchemaUtils {

	private static final Logger logger = LoggerFactory.getLogger(AvroSchemaUtils.class);
	private static final Gson GSON = new GsonBuilder().create();
	
	public static List<Schema> getSchemasAsList(String schemas) {
		if (Strings.isNullOrEmpty(schemas)) {
			return null;
		}
		try {
			List<Schema> schemaList = new ArrayList<>();
			JsonElement root = new JsonParser().parse(schemas);
			if (root.isJsonArray()) {
				JsonArray array = root.getAsJsonArray();
				for (int i = 0; i < array.size(); i++) {
					String jsonStr = array.get(i).toString();
					Schema schema = new Schema.Parser().parse(jsonStr);
					schemaList.add(schema);
				}
			} else if (root.isJsonObject()) {
				JsonObject obj = root.getAsJsonObject();
				String jsonStr = obj.toString();
				Schema schema = new Schema.Parser().parse(jsonStr);
				schemaList.add(schema);
			}
			return schemaList;
		} catch (Exception e) {
			logger.warn("Schema parsing failed", e);
			return null;
		}
	}

	public static Map<String, String> getSchemasAsStringMap(String schemas) {
		List<Schema> schemaList = getSchemasAsList(schemas);
		if (schemaList == null) {
			return null;
		}
		Map<String, String> schemaMap = new HashMap<>();
		for (Schema schema : schemaList) {
			String key = schema.getFullName();
			schemaMap.put(key, schema.toString());
		}
		return schemaMap;
	}

	/*
	 *  { "schema1.table1" : "schema2.table1", "table1" : "table2" }
	 */
	
	public static Map<String, String> replaceTableNameByEnv(Map<String, String> schemaMap, String mapString) {
		
		try{
			if(mapString != null && !mapString.isEmpty()){
				logger.info("Replace table in env found: " + mapString);
				Type mapType = new TypeToken<Map<String, String>>(){}.getType();  
				Map<String, String> map = GSON.fromJson(mapString, mapType);
				Map<String, String> nodeMap = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
				nodeMap.putAll(map);
				Map<String, String> newSchemaMap = new HashMap<String, String>();
				for(String k : schemaMap.keySet()){
					if(nodeMap.containsKey(k)){
						String newName = nodeMap.get(k);
						logger.info("Replace table: " + k +" to " + newName);
						newSchemaMap.put(newName, schemaMap.get(k));
					} else
						newSchemaMap.put(k, schemaMap.get(k));
				}
				return newSchemaMap;
			} else {
				logger.info("No replace table in env found: ");
			}
		} catch (Exception e){
			logger.error("Table name replace failed, keep orginal name", e);
		}

		return schemaMap;

	}
	
	/*
	 *  { "schema1" : "schema2"}
	 */
	public static Map<String, String> replaceSchemaNameByEnv(Map<String, String> schemaMap, String mapString) {
		
		try{
			if(mapString != null && !mapString.isEmpty()){
				logger.info("Replace schema in env found: " + mapString);
				Type mapType = new TypeToken<Map<String, String>>(){}.getType();  
				Map<String, String> map = GSON.fromJson(mapString, mapType);
				Map<String, String> nodeMap = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
				nodeMap.putAll(map);
				Map<String, String> newSchemaMap = new HashMap<String, String>();
				for(String k : schemaMap.keySet()){
					 String [] content = k.split("\\.");
					 if(content.length<2){
						 logger.warn("Table name: "+k+" has no schema name, skipping");
					 	 newSchemaMap.put(k, schemaMap.get(k));
					 } else {
						if(nodeMap.containsKey(content[0])){
						 String newName = nodeMap.get(content[0]);
						 for(int i = 1;i<content.length;i++){
							 newName = newName+"."+content[i];
						 }
						 logger.info("Replace table: " + k +" to " + newName);						
						 newSchemaMap.put(newName, schemaMap.get(k));
						}
					 }
				}
				return newSchemaMap;
			} else {
				logger.info("No replace schema in parameter found: ");
			}
		} catch (Exception e){
			logger.error("Table name replace failed, keep orginal name", e);
		}

		return schemaMap;

	}
	
	@Deprecated
	public static String replaceSchemaNameOfTableByEnv(String tableName, String mapString) {
		try{
			if(mapString != null && !mapString.isEmpty()){
				Type mapType = new TypeToken<Map<String, String>>(){}.getType();  
				Map<String, String> map = GSON.fromJson(mapString, mapType);
				Map<String, String> nodeMap = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
				nodeMap.putAll(map);
				String content[] = tableName.split("\\.");
				 if(content.length<2){
					 logger.warn("Table name: "+tableName+" has no schema name, skipping");
					 return tableName;
				 } else {
					 if(nodeMap.containsKey(content[0])){
						 String newName = nodeMap.get(content[0]);
						 for(int i = 1;i<content.length;i++){
							 newName = newName+"."+content[i];
						 }
						 logger.info("Replace table: " + tableName +" to " + newName);						
						 return tableName;
					 }
				 }
				
			}
		} catch (Exception e){
			logger.error("Table name replace failed, keep orginal name", e);
		}
		return tableName;

	}
	
	@SuppressWarnings("unchecked")
	public static String getReplaceSchemas(Context context) {
		String userParamsStr = context.getString("userParams");
		if (userParamsStr != null) {
			try {
				Map<String, String> userParams = GSON.fromJson(userParamsStr, Map.class);
				if (userParams != null) {
					return userParams.get(SqlSourceUtil.SCHEMA_NAME_REPLACE_ENV);
				}
			} catch (JsonSyntaxException e) {
				logger.warn("Can't parse userParams", e);
			}
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	public static String getReplaceTriggerTable(Context context) {
		String userParamsStr = context.getString("userParams");
		if (userParamsStr != null) {
			try {
				Map<String, String> userParams = GSON.fromJson(userParamsStr, Map.class);
				if (userParams != null) {
					return userParams.get(SqlSourceUtil.TRIGGER_TABLE_NAME_REPLACE_ENV);
				}
			} catch (JsonSyntaxException e) {
				logger.warn("Can't parse userParams", e);
			}
		}
		return null;
	}
	
	public static String replaceTableNameOfTableByEnv(String tableName, String mapString) {
		try{
			if(mapString != null && !mapString.isEmpty()){
				Type mapType = new TypeToken<Map<String, String>>(){}.getType();  
				Map<String, String> map = GSON.fromJson(mapString, mapType);
				Map<String, String> nodeMap = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
				nodeMap.putAll(map);

				if(nodeMap.containsKey(tableName)){
					String newName = nodeMap.get(tableName);
					logger.info("Replace table: " + tableName +" to " + newName);
					return newName;
				}


			}
		} catch (Exception e){
			logger.error("Table name replace failed, keep orginal name", e);
		}
		return tableName;

	}


}
