package com.ingestpipeline.consumer;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

@SuppressWarnings("rawtypes")
public class HashMapDeserializer extends JsonDeserializer<HashMap> {

	public static final Logger LOGGER = LoggerFactory.getLogger(DigressionConsumer.class);
	public HashMapDeserializer() {		
		super(HashMap.class);
		LOGGER.info("----INSIDE HashMapDeserializer OF HashMapDeserializer ----");
	}

}