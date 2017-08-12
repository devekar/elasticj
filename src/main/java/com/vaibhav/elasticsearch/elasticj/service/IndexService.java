package com.vaibhav.elasticsearch.elasticj.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;


@Service
public class IndexService {
	private static final Logger LOG = LoggerFactory.getLogger(IndexService.class);

	private static HashSet<String> FIELDS = new HashSet<>();
	private ResourceLoader resourceLoader;
	private Client client;
	private Environment environment;

	@Autowired
	public IndexService(Client client,
			ResourceLoader resourceLoader,
			Environment environment) {
		this.client = client;
		this.resourceLoader = resourceLoader;
		this.environment = environment;
	}

	public void index() throws IOException {
		HashSet<String> fields = getFieldsToIndex();    	
		FIELDS = fields;
		//uploadFileData();
		uploadWithBulkProcessorBySize();
		//initiateShutdown(0);
	}

	private HashSet<String> getFieldsToIndex() throws IOException {
		HashSet<String> fields = new HashSet<>();
		Resource resource = resourceLoader.getResource("classpath:fieldList.txt");
		InputStream in = resource.getInputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));

		String line = reader.readLine();
		while(line != null && !line.isEmpty()) {
			fields.add(line.trim());
			line = reader.readLine();
		}

		LOG.info("Fields to be uploaded: "+ fields.toString());
		return fields;
	}

	private void uploadWithBulkProcessorBySize() throws IOException {
		Long startTime = System.currentTimeMillis();

		Resource resource = resourceLoader.getResource("file:" + environment.getRequiredProperty("feed.path"));
		InputStream in = resource.getInputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));
		String index = "dotcom";

		//delete index if exists
		if (client.admin().indices().prepareExists(index).execute().actionGet().isExists()) {
			client.admin().indices().prepareDelete(index).execute().actionGet();
		}

		final BulkProcessor bp = getBulkProcessor();
		int i = 0;

		try {
			Settings settings = Settings.builder()
					.put("number_of_shards", 1)
					.put("number_of_replicas", 0)
					.build();

			client.admin().indices().prepareCreate(index).setSettings(settings).execute().actionGet();
			client.admin().cluster().prepareHealth(index).setWaitForYellowStatus().execute().actionGet();


			while (true) {			
				Map<String, String> recordMap = readFeedRecord(reader);
				if(recordMap.isEmpty()) {
					break;
				} 

				bp.add(Requests.indexRequest(index).type("product").source(recordMap));
				i++;

				if(i%10000 == 0) {
					LOG.debug("Processed documents: " + i);
				}
			}




			client.admin().indices()
			.prepareRefresh(index)               
			.get();
		} finally {
			bp.close();
			reader.close();  
		}

		LOG.info("\nUploaded total documents: " + i);
		LOG.info("Time Taken: > {} m {} s", (System.currentTimeMillis() - startTime)/(1000*60), (System.currentTimeMillis() - startTime)/1000%60);
	}

	private BulkProcessor getBulkProcessor() {
		final BulkProcessor bp = BulkProcessor.builder(client, new BulkProcessor.Listener() {
			@Override
			public void beforeBulk(long executionId, BulkRequest request) {
			}

			@Override
			public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
				System.out.println("Bulk execution failed ["+  executionId + "].\n" +
						failure.toString());
			}

			@Override
			public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
				System.out.println("Bulk execution completed ["+  executionId + "].\n" +
						"Took (ms): " + response.getTookInMillis() + "\n" +
						"Failures: " + response.hasFailures() + "\n" + 
						"Count: " + response.getItems().length);
			}
		})
				.setConcurrentRequests(4)
				.setBulkActions(-1)
				.setBulkSize(new ByteSizeValue(1, ByteSizeUnit.MB))
				.build();

		return bp;
	}


	private void uploadFileData() throws IOException {
		Long startTime = System.currentTimeMillis();

		Resource resource = resourceLoader.getResource("file:" + environment.getRequiredProperty("feed.path"));
		InputStream in = resource.getInputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));

		BulkRequestBuilder bulkRequest = client.prepareBulk();

		int i = 0;
		while (true) {			
			Map<String, String> recordMap = readFeedRecord(reader);
			if(recordMap.isEmpty()) {
				break;
			} 

			bulkRequest.add(
					client.prepareIndex("dotcom", "product", "" + i)
					.setSource(recordMap));
			i++;

			if(i%10000 == 0) {
				BulkResponse bulkResponse = bulkRequest.get();
				if (bulkResponse.hasFailures()) {
					throw new IOException("Bulk indexing failed at " + i);
				}

				LOG.debug("Uploaded documents: " + i);
			}
		}

		if(i%10000 > 0) {
			BulkResponse bulkResponse = bulkRequest.get();
			if (bulkResponse.hasFailures()) {
				throw new IOException("Bulk indexing failed at " + i);
			}
		}

		LOG.info("\nUploaded total documents: " + i);
		LOG.info("Time Taken: > {} m {} s", (System.currentTimeMillis() - startTime)/(1000*60), (System.currentTimeMillis() - startTime)/1000%60);

		reader.close();  
	}

	private Map<String, String> readFeedRecord(BufferedReader reader) throws IOException {        

		Map<String, String> recordMap = new HashMap<String, String>();
		String line = reader.readLine();
		while(line !=null && !line.equals("REC")) {
			String[] pair = line.split("\\|!");
			if(pair.length == 2 && FIELDS.contains(pair[0])) {
				recordMap.put(pair[0], pair[1]);                
			} else {
				//System.out.println("WARN: Invalid field: " + line);
			}
			line = reader.readLine();
		}

		return recordMap;
	}

	private void deleteFromIndex() throws IOException {
		Long startTime = System.currentTimeMillis();

		String index = "dotcom";	

		final BulkProcessor bp = getBulkProcessor();
		int i = 0;

		List<String> blacklistSkus = readBlacklistFile();
		String blacklistField = environment.getRequiredProperty("blacklist.field");

		BulkByScrollResponse response =
				DeleteByQueryAction.INSTANCE.newRequestBuilder(client)
				.filter(QueryBuilders.termsQuery(blacklistField, blacklistSkus)) 
				.source(index)
				.get();                                             

		long deleted = response.getDeleted();  
		if(deleted == blacklistSkus.size()) {
			LOG.info("Deleted {} skus", deleted);
		} else {
			throw new IOException("Failed to delete some skus. Deleted "
					+ deleted + " of " + blacklistSkus.size());
		}
	}

	private List<String> readBlacklistFile() throws IOException {
		Resource resource = resourceLoader.getResource("file:" + environment.getRequiredProperty("blacklist.path"));
		InputStream in = resource.getInputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));

		List<String> blacklistSkus = new ArrayList<String>();
		String line = reader.readLine();
		while(line !=null) {
			line = line.trim();
			if(line.isEmpty()) {
				break;
			}
			blacklistSkus.add(line);
			line = reader.readLine();
		}
		
		return null;
	}

}
