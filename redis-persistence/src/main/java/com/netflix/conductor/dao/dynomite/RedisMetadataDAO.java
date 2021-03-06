/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.conductor.dao.dynomite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.inject.Singleton;
import com.netflix.conductor.annotations.Trace;
import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.core.execution.ApplicationException;
import com.netflix.conductor.core.execution.ApplicationException.Code;
import com.netflix.conductor.dao.MetadataDAO;

@Singleton
@Trace
public class RedisMetadataDAO extends BaseDynoDAO implements MetadataDAO {

	// Keys Families
	private final static String ALL_TASK_DEFS = "TASK_DEFS";
	private final static String WORKFLOW_DEF_NAMES = "WORKFLOW_DEF_NAMES";
	private final static String WORKFLOW_DEF = "WORKFLOW_DEF";
	private final static String LATEST = "latest";

	@Inject
	public RedisMetadataDAO(DynoProxy dynoClient, ObjectMapper om, Configuration config) {
		super(dynoClient, om, config);
	}

	@Override
	public String createTaskDef(TaskDef taskDef) {
		taskDef.setCreateTime(System.currentTimeMillis());
		return insertOrUpdateTaskDef(taskDef);
	}

	@Override
	public String updateTaskDef(TaskDef taskDef) {
		taskDef.setUpdateTime(System.currentTimeMillis());
		return insertOrUpdateTaskDef(taskDef);
	}

	private String insertOrUpdateTaskDef(TaskDef taskDef) {

		Preconditions.checkNotNull(taskDef, "TaskDef object cannot be null");
		Preconditions.checkNotNull(taskDef.getName(), "TaskDef name cannot be null");

		// Store all task def in under one key
		dynoClient.hset(nsKey(ALL_TASK_DEFS), taskDef.getName(), toJson(taskDef));

		return taskDef.getName();
	}

	@Override
	public TaskDef getTaskDef(String name) {
		Preconditions.checkNotNull(name, "TaskDef name cannot be null");
		TaskDef taskDef = null;

		String taskDefJsonStr = dynoClient.hget(nsKey(ALL_TASK_DEFS), name);
		if (taskDefJsonStr != null) {
			taskDef = readValue(taskDefJsonStr, TaskDef.class);
		}

		return taskDef;
	}

	@Override
	public List<TaskDef> getAllTaskDefs() {
		List<TaskDef> allTaskDefs = new LinkedList<TaskDef>();

		Map<String, String> taskDefs = dynoClient.hgetAll(nsKey(ALL_TASK_DEFS));
		if (taskDefs.size() > 0) {
			for (String taskDefJsonStr : taskDefs.values()) {
				if (taskDefJsonStr != null) {
					allTaskDefs.add(readValue(taskDefJsonStr, TaskDef.class));
				}
			}
		}

		return allTaskDefs;
	}

	@Override
	public void removeTaskDef(String name) {
		Preconditions.checkNotNull(name, "TaskDef name cannot be null");
		Long result = dynoClient.hdel(nsKey(ALL_TASK_DEFS), name);
		if (!result.equals(1L)) {
			throw new ApplicationException(Code.NOT_FOUND, "Cannot remove the task - no such task definition");
		}
	}

	@Override
	public void create(WorkflowDef def) {
		if (dynoClient.hexists(nsKey(WORKFLOW_DEF, def.getName()), String.valueOf(def.getVersion()))) {
			throw new ApplicationException(Code.CONFLICT, "Workflow with " + def.key() + " already exists!");
		}
		def.setCreateTime(System.currentTimeMillis());
		_createOrUpdate(def);
	}

	@Override
	public void update(WorkflowDef def) {
		def.setUpdateTime(System.currentTimeMillis());
		_createOrUpdate(def);
	}

	@Override
	public WorkflowDef getLatest(String name) {
		Preconditions.checkNotNull(name, "WorkflowDef name cannot be null");
		WorkflowDef def = null;

		String wfDefJsonStr = dynoClient.hget(nsKey(WORKFLOW_DEF, name), LATEST);
		if (wfDefJsonStr != null) {
			def = readValue(wfDefJsonStr, WorkflowDef.class);
		}

		return def;
	}

	public List<WorkflowDef> getAllVersions(String name) {
		Preconditions.checkNotNull(name, "WorkflowDef name cannot be null");
		List<WorkflowDef> workflows = new LinkedList<WorkflowDef>();

		Map<String, String> wfs = dynoClient.hgetAll(nsKey(WORKFLOW_DEF, name));
		for (String key : wfs.keySet()) {
			if (key.equals(LATEST)) {
				continue;
			}
			workflows.add(readValue(wfs.get(key), WorkflowDef.class));
		}

		return workflows;
	}

	@Override
	public WorkflowDef get(String name, int version) {
		Preconditions.checkNotNull(name, "WorkflowDef name cannot be null");
		WorkflowDef def = null;

		String wfDefJsonStr = dynoClient.hget(nsKey(WORKFLOW_DEF, name), String.valueOf(version));
		if (wfDefJsonStr != null) {
			def = readValue(wfDefJsonStr, WorkflowDef.class);
		}

		return def;
	}

	@Override
	public List<String> findAll() {
		Set<String> wfNames = dynoClient.smembers(nsKey(WORKFLOW_DEF_NAMES));
		return wfNames.stream().collect(Collectors.toList());
	}

	@Override
	public List<WorkflowDef> getAll() {
		List<WorkflowDef> workflows = new LinkedList<WorkflowDef>();

		// Get all from WORKFLOW_DEF_NAMES
		Set<String> wfNames = dynoClient.smembers(nsKey(WORKFLOW_DEF_NAMES));
		for (String wfName : wfNames) {
			Map<String, String> wfs = dynoClient.hgetAll(nsKey(WORKFLOW_DEF, wfName));
			for (String key : wfs.keySet()) {
				if (key.equals(LATEST)) {
					continue;
				}
				workflows.add(readValue(wfs.get(key), WorkflowDef.class));
			}
		}

		return workflows;
	}

	@Override
	public List<WorkflowDef> getAllLatest() {
		List<WorkflowDef> workflows = new LinkedList<WorkflowDef>();

		// Get all from WORKFLOW_DEF_NAMES
		Set<String> wfNames = dynoClient.smembers(nsKey(WORKFLOW_DEF_NAMES));
		for (String wfName : wfNames) {
			String wfDefJsonStr = dynoClient.hget(nsKey(WORKFLOW_DEF, wfName), LATEST);
			workflows.add(readValue(wfDefJsonStr, WorkflowDef.class));
		}

		return workflows;
	}

	private void _createOrUpdate(WorkflowDef def) {
		Preconditions.checkNotNull(def, "WorkflowDef object cannot be null");
		Preconditions.checkNotNull(def.getName(), "WorkflowDef name cannot be null");

		// First set the workflow def
		dynoClient.hset(nsKey(WORKFLOW_DEF, def.getName()), String.valueOf(def.getVersion()), toJson(def));

		// If it is getting created then make sure the latest field is updated
		// and WORKFLOW_DEF_NAMES is updated
		List<Integer> versions = new ArrayList<Integer>();
		dynoClient.hkeys(nsKey(WORKFLOW_DEF, def.getName())).forEach(key -> {
			if (!key.equals(LATEST)) {
				versions.add(Integer.valueOf(key));
			}
		});
		Collections.sort(versions);
		String latestKey = versions.get(versions.size() - 1).toString();
		String latestdata = dynoClient.hget(nsKey(WORKFLOW_DEF, def.getName()), latestKey);
		dynoClient.hset(nsKey(WORKFLOW_DEF, def.getName()), LATEST, latestdata);
		dynoClient.sadd(nsKey(WORKFLOW_DEF_NAMES), def.getName());

	}
}
