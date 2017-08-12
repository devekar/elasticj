package com.vaibhav.elasticsearch.elasticj.controller;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vaibhav.elasticsearch.elasticj.service.IndexService;

import org.springframework.http.HttpStatus;


@RestController
public class IndexController {

	@Autowired
	private IndexService indexService;

	@ResponseStatus(HttpStatus.OK)
	@RequestMapping("/trigger")
	public void trigger() throws IOException {
		indexService.index();
	}
	
}
