/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Joiner;
import com.google.common.io.Files;
import org.apache.commons.beanutils.BeanMap;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.dolphinscheduler.api.dto.resources.ResourceComponent;
import org.apache.dolphinscheduler.api.dto.resources.filter.ResourceFilter;
import org.apache.dolphinscheduler.api.dto.resources.visitor.ResourceTreeVisitor;
import org.apache.dolphinscheduler.api.dto.resources.visitor.Visitor;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.exceptions.ServiceException;
import org.apache.dolphinscheduler.api.service.ResourcesService;
import org.apache.dolphinscheduler.api.utils.PageInfo;
import org.apache.dolphinscheduler.api.utils.RegexUtils;
import org.apache.dolphinscheduler.api.utils.Result;
import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.ProgramType;
import org.apache.dolphinscheduler.common.enums.ResUploadType;
import org.apache.dolphinscheduler.common.storage.StorageOperate;
import org.apache.dolphinscheduler.common.utils.FileUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.PropertyUtils;
import org.apache.dolphinscheduler.dao.entity.Resource;
import org.apache.dolphinscheduler.dao.entity.ResourcesUser;
import org.apache.dolphinscheduler.dao.entity.Tenant;
import org.apache.dolphinscheduler.dao.entity.UdfFunc;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.mapper.ProcessDefinitionMapper;
import org.apache.dolphinscheduler.dao.mapper.ResourceMapper;
import org.apache.dolphinscheduler.dao.mapper.ResourceUserMapper;
import org.apache.dolphinscheduler.dao.mapper.TenantMapper;
import org.apache.dolphinscheduler.dao.mapper.UdfFuncMapper;
import org.apache.dolphinscheduler.dao.mapper.UserMapper;
import org.apache.dolphinscheduler.dao.utils.ResourceProcessDefinitionUtils;
import org.apache.dolphinscheduler.spi.enums.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.rmi.ServerException;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static org.apache.dolphinscheduler.common.Constants.ALIAS;
import static org.apache.dolphinscheduler.common.Constants.CONTENT;
import static org.apache.dolphinscheduler.common.Constants.FOLDER_SEPARATOR;
import static org.apache.dolphinscheduler.common.Constants.FORMAT_SS;
import static org.apache.dolphinscheduler.common.Constants.FORMAT_S_S;
import static org.apache.dolphinscheduler.common.Constants.JAR;

/**
 * resources service impl
 */
@Service
public class ResourcesServiceImpl extends BaseServiceImpl implements ResourcesService {

    private static final Logger logger = LoggerFactory.getLogger(ResourcesServiceImpl.class);

    @Autowired
    private ResourceMapper resourcesMapper;

    @Autowired
    private UdfFuncMapper udfFunctionMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ResourceUserMapper resourceUserMapper;

    @Autowired
    private ProcessDefinitionMapper processDefinitionMapper;

    @Autowired(required = false)
    private StorageOperate storageOperate;

    /**
     * create directory
     *
     * @param loginUser   login user
     * @param name        alias
     * @param description description
     * @param type        type
     * @param pid         parent id
     * @param currentDir  current directory
     * @return create directory result
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> createDirectory(User loginUser,
                                          String name,
                                          String description,
                                          ResourceType type,
                                          int pid,
                                          String currentDir) {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }
        if (name.endsWith(FOLDER_SEPARATOR)) {
            result.setCode(Status.VERIFY_PARAMETER_NAME_FAILED.getCode());
            return result;
        }
        String fullName = getFullName(currentDir, name);
        result = verifyResource(loginUser, type, fullName, pid);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        if (checkResourceExists(fullName, type.ordinal())) {
            logger.error("resource directory {} has exist, can't recreate", fullName);
            putMsg(result, Status.RESOURCE_EXIST);
            return result;
        }

        Date now = new Date();

        Resource resource = new Resource(pid, name, fullName, true, description, name, loginUser.getId(), type, 0, now, now);

        try {
            resourcesMapper.insert(resource);
            putMsg(result, Status.SUCCESS);
            Map<String, Object> resultMap = new HashMap<>();
            for (Map.Entry<Object, Object> entry : new BeanMap(resource).entrySet()) {
                if (!"class".equalsIgnoreCase(entry.getKey().toString())) {
                    resultMap.put(entry.getKey().toString(), entry.getValue());
                }
            }
            result.setData(resultMap);
        } catch (DuplicateKeyException e) {
            logger.error("resource directory {} has exist, can't recreate", fullName);
            putMsg(result, Status.RESOURCE_EXIST);
            return result;
        } catch (Exception e) {
            logger.error("resource already exists, can't recreate ", e);
            throw new ServiceException("resource already exists, can't recreate");
        }
        //create directory in storage
        createDirectory(loginUser, fullName, type, result);
        return result;
    }

    private String getFullName(String currentDir, String name) {
        return currentDir.equals(FOLDER_SEPARATOR) ? String.format(FORMAT_SS, currentDir, name) : String.format(FORMAT_S_S, currentDir, name);
    }

    /**
     * create resource
     *
     * @param loginUser  login user
     * @param name       alias
     * @param desc       description
     * @param file       file
     * @param type       type
     * @param pid        parent id
     * @param currentDir current directory
     * @return create result code
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> createResource(User loginUser,
                                         String name,
                                         String desc,
                                         ResourceType type,
                                         MultipartFile file,
                                         int pid,
                                         String currentDir) {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        result = verifyPid(loginUser, pid);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        result = verifyFile(name, type, file);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        // check resource name exists
        String fullName = getFullName(currentDir, name);
        if (checkResourceExists(fullName, type.ordinal())) {
            logger.error("resource {} has exist, can't recreate", RegexUtils.escapeNRT(name));
            putMsg(result, Status.RESOURCE_EXIST);
            return result;
        }
        if (fullName.length() > Constants.RESOURCE_FULL_NAME_MAX_LENGTH) {
            logger.error("resource {}'s full name {}' is longer than the max length {}", RegexUtils.escapeNRT(name), fullName, Constants.RESOURCE_FULL_NAME_MAX_LENGTH);
            putMsg(result, Status.RESOURCE_FULL_NAME_TOO_LONG_ERROR);
            return result;
        }

        Date now = new Date();
        Resource resource = new Resource(pid, name, fullName, false, desc, file.getOriginalFilename(), loginUser.getId(), type, file.getSize(), now, now);

        try {
            resourcesMapper.insert(resource);
            updateParentResourceSize(resource, resource.getSize());
            putMsg(result, Status.SUCCESS);
            Map<String, Object> resultMap = new HashMap<>();
            for (Map.Entry<Object, Object> entry : new BeanMap(resource).entrySet()) {
                if (!"class".equalsIgnoreCase(entry.getKey().toString())) {
                    resultMap.put(entry.getKey().toString(), entry.getValue());
                }
            }
            result.setData(resultMap);
        } catch (Exception e) {
            logger.error("resource already exists, can't recreate ", e);
            throw new ServiceException("resource already exists, can't recreate");
        }

        // fail upload
        if (!upload(loginUser, fullName, file, type)) {
            logger.error("upload resource: {} file: {} failed.", RegexUtils.escapeNRT(name), RegexUtils.escapeNRT(file.getOriginalFilename()));
            putMsg(result, Status.STORE_OPERATE_CREATE_ERROR);
            throw new ServiceException(String.format("upload resource: %s file: %s failed.", name, file.getOriginalFilename()));
        }
        return result;
    }

    /**
     * update the folder's size of the resource
     *
     * @param resource the current resource
     * @param size size
     */
    private void updateParentResourceSize(Resource resource, long size) {
        if (resource.getSize() > 0) {
            String[] splits = resource.getFullName().split("/");
            for (int i = 1; i < splits.length; i++) {
                String parentFullName = Joiner.on("/").join(Arrays.copyOfRange(splits, 0, i));
                if (StringUtils.isNotBlank(parentFullName)) {
                    List<Resource> resources = resourcesMapper.queryResource(parentFullName, resource.getType().ordinal());
                    if (CollectionUtils.isNotEmpty(resources)) {
                        Resource parentResource = resources.get(0);
                        if (parentResource.getSize() + size >= 0) {
                            parentResource.setSize(parentResource.getSize() + size);
                        } else {
                            parentResource.setSize(0L);
                        }
                        resourcesMapper.updateById(parentResource);
                    }
                }
            }
        }
    }

    /**
     * check resource is exists
     *
     * @param fullName fullName
     * @param type     type
     * @return true if resource exists
     */
    private boolean checkResourceExists(String fullName, int type) {
        Boolean existResource = resourcesMapper.existResource(fullName, type);
        return Boolean.TRUE.equals(existResource);
    }

    /**
     * update resource
     *
     * @param loginUser  login user
     * @param resourceId resource id
     * @param name       name
     * @param desc       description
     * @param type       resource type
     * @param file       resource file
     * @return update result code
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> updateResource(User loginUser,
                                         int resourceId,
                                         String name,
                                         String desc,
                                         ResourceType type,
                                         MultipartFile file) {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }


        Resource resource = resourcesMapper.selectById(resourceId);
        if (resource == null) {
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            return result;
        }

        if (!PropertyUtils.getResUploadStartupState()){
            putMsg(result, Status.STORAGE_NOT_STARTUP);
            return result;
        }

        if (resource.isDirectory() && storageOperate.returnStorageType().equals(ResUploadType.S3) && !resource.getFileName().equals(name)) {
            putMsg(result, Status.S3_CANNOT_RENAME);
            return result;
        }

        if (!canOperator(loginUser, resource.getUserId())) {
            putMsg(result, Status.USER_NO_OPERATION_PERM);
            return result;
        }

        if (file == null && name.equals(resource.getAlias()) && desc.equals(resource.getDescription())) {
            putMsg(result, Status.SUCCESS);
            return result;
        }

        //check resource already exists
        String originFullName = resource.getFullName();
        String originResourceName = resource.getAlias();

        String fullName = String.format(FORMAT_SS, originFullName.substring(0, originFullName.lastIndexOf(FOLDER_SEPARATOR) + 1), name);
        if (!originResourceName.equals(name) && checkResourceExists(fullName, type.ordinal())) {
            logger.error("resource {} already exists, can't recreate", name);
            putMsg(result, Status.RESOURCE_EXIST);
            return result;
        }

        result = verifyFile(name, type, file);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        // query tenant by user id
        String tenantCode = getTenantCode(resource.getUserId(), result);
        if (StringUtils.isEmpty(tenantCode)) {
            return result;
        }
        // verify whether the resource exists in storage
        // get the path of origin file in storage
        String originFileName = storageOperate.getFileName(resource.getType(), tenantCode, originFullName);
        try {
            if (!storageOperate.exists(tenantCode, originFileName)) {
                logger.error("{} not exist", originFileName);
                putMsg(result, Status.RESOURCE_NOT_EXIST);
                return result;
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new ServiceException(Status.HDFS_OPERATION_ERROR);
        }

        if (!resource.isDirectory()) {
            //get the origin file suffix
            String originSuffix = Files.getFileExtension(originFullName);
            String suffix = Files.getFileExtension(fullName);
            boolean suffixIsChanged = false;
            if (StringUtils.isBlank(suffix) && StringUtils.isNotBlank(originSuffix)) {
                suffixIsChanged = true;
            }
            if (StringUtils.isNotBlank(suffix) && !suffix.equals(originSuffix)) {
                suffixIsChanged = true;
            }
            //verify whether suffix is changed
            if (suffixIsChanged) {
                //need verify whether this resource is authorized to other users
                Map<String, Object> columnMap = new HashMap<>();
                columnMap.put("resources_id", resourceId);

                List<ResourcesUser> resourcesUsers = resourceUserMapper.selectByMap(columnMap);
                if (CollectionUtils.isNotEmpty(resourcesUsers)) {
                    List<Integer> userIds = resourcesUsers.stream().map(ResourcesUser::getUserId).collect(Collectors.toList());
                    List<User> users = userMapper.selectBatchIds(userIds);
                    String userNames = users.stream().map(User::getUserName).collect(Collectors.toList()).toString();
                    logger.error("resource is authorized to user {},suffix not allowed to be modified", userNames);
                    putMsg(result, Status.RESOURCE_IS_AUTHORIZED, userNames);
                    return result;
                }
            }
        }

        // updateResource data
        Date now = new Date();
        long originFileSize = resource.getSize();

        resource.setAlias(name);
        resource.setFileName(name);
        resource.setFullName(fullName);
        resource.setDescription(desc);
        resource.setUpdateTime(now);
        if (file != null) {
            resource.setSize(file.getSize());
        }

        try {
            resourcesMapper.updateById(resource);
            if (resource.isDirectory()) {
                List<Integer> childrenResource = listAllChildren(resource, false);
                if (CollectionUtils.isNotEmpty(childrenResource)) {
                    String matcherFullName = Matcher.quoteReplacement(fullName);
                    List<Resource> childResourceList;
                    Integer[] childResIdArray = childrenResource.toArray(new Integer[childrenResource.size()]);
                    List<Resource> resourceList = resourcesMapper.listResourceByIds(childResIdArray);
                    childResourceList = resourceList.stream().map(t -> {
                        t.setFullName(t.getFullName().replaceFirst(originFullName, matcherFullName));
                        t.setUpdateTime(now);
                        return t;
                    }).collect(Collectors.toList());
                    resourcesMapper.batchUpdateResource(childResourceList);

                    if (ResourceType.UDF.equals(resource.getType())) {
                        List<UdfFunc> udfFuncs = udfFunctionMapper.listUdfByResourceId(childResIdArray);
                        if (CollectionUtils.isNotEmpty(udfFuncs)) {
                            udfFuncs = udfFuncs.stream().map(t -> {
                                t.setResourceName(t.getResourceName().replaceFirst(originFullName, matcherFullName));
                                t.setUpdateTime(now);
                                return t;
                            }).collect(Collectors.toList());
                            udfFunctionMapper.batchUpdateUdfFunc(udfFuncs);
                        }
                    }
                }
            } else if (ResourceType.UDF.equals(resource.getType())) {
                List<UdfFunc> udfFuncs = udfFunctionMapper.listUdfByResourceId(new Integer[]{resourceId});
                if (CollectionUtils.isNotEmpty(udfFuncs)) {
                    udfFuncs = udfFuncs.stream().map(t -> {
                        t.setResourceName(fullName);
                        t.setUpdateTime(now);
                        return t;
                    }).collect(Collectors.toList());
                    udfFunctionMapper.batchUpdateUdfFunc(udfFuncs);
                }

            }

            putMsg(result, Status.SUCCESS);
            Map<String, Object> resultMap = new HashMap<>();
            for (Map.Entry<Object, Object> entry : new BeanMap(resource).entrySet()) {
                if (!Constants.CLASS.equalsIgnoreCase(entry.getKey().toString())) {
                    resultMap.put(entry.getKey().toString(), entry.getValue());
                }
            }
            result.setData(resultMap);
        } catch (Exception e) {
            logger.error(Status.UPDATE_RESOURCE_ERROR.getMsg(), e);
            throw new ServiceException(Status.UPDATE_RESOURCE_ERROR);
        }

        // if name unchanged, return directly without moving on HDFS
        if (originResourceName.equals(name) && file == null) {
            return result;
        }

        if (file != null) {
            // fail upload
            if (!upload(loginUser, fullName, file, type)) {
                logger.error("upload resource: {} file: {} failed.", name, RegexUtils.escapeNRT(file.getOriginalFilename()));
                putMsg(result, Status.HDFS_OPERATION_ERROR);
                throw new ServiceException(String.format("upload resource: %s file: %s failed.", name, file.getOriginalFilename()));
            }
            if (!fullName.equals(originFullName)) {
                try {
                    storageOperate.delete(tenantCode, originFileName, false);
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                    throw new ServiceException(String.format("delete resource: %s failed.", originFullName));
                }
            }

            updateParentResourceSize(resource, resource.getSize() - originFileSize);
            return result;
        }

        // get the path of dest file in hdfs
        String destHdfsFileName = storageOperate.getFileName(resource.getType(), tenantCode, fullName);

        try {
            logger.info("start  copy {} -> {}", originFileName, destHdfsFileName);
            storageOperate.copy(originFileName, destHdfsFileName, true, true);
        } catch (Exception e) {
            logger.error(MessageFormat.format(" copy {0} -> {1} fail", originFileName, destHdfsFileName), e);
            putMsg(result, Status.HDFS_COPY_FAIL);
            throw new ServiceException(Status.HDFS_COPY_FAIL);
        }

        return result;
    }

    private Result<Object> verifyFile(String name, ResourceType type, MultipartFile file) {
        Result<Object> result = new Result<>();
        putMsg(result, Status.SUCCESS);
        if (file != null) {
            // file is empty
            if (file.isEmpty()) {
                logger.error("file is empty: {}", RegexUtils.escapeNRT(file.getOriginalFilename()));
                putMsg(result, Status.RESOURCE_FILE_IS_EMPTY);
                return result;
            }

            // file suffix
            String fileSuffix = Files.getFileExtension(file.getOriginalFilename());
            String nameSuffix = Files.getFileExtension(name);

            // determine file suffix
            if (!fileSuffix.equalsIgnoreCase(nameSuffix)) {
                // rename file suffix and original suffix must be consistent
                logger.error("rename file suffix and original suffix must be consistent: {}", RegexUtils.escapeNRT(file.getOriginalFilename()));
                putMsg(result, Status.RESOURCE_SUFFIX_FORBID_CHANGE);
                return result;
            }

            //If resource type is UDF, only jar packages are allowed to be uploaded, and the suffix must be .jar
            if (Constants.UDF.equals(type.name()) && !JAR.equalsIgnoreCase(fileSuffix)) {
                logger.error(Status.UDF_RESOURCE_SUFFIX_NOT_JAR.getMsg());
                putMsg(result, Status.UDF_RESOURCE_SUFFIX_NOT_JAR);
                return result;
            }
            if (file.getSize() > Constants.MAX_FILE_SIZE) {
                logger.error("file size is too large: {}", RegexUtils.escapeNRT(file.getOriginalFilename()));
                putMsg(result, Status.RESOURCE_SIZE_EXCEED_LIMIT);
                return result;
            }
        }
        return result;
    }

    /**
     * query resources list paging
     *
     * @param loginUser login user
     * @param type      resource type
     * @param searchVal search value
     * @param pageNo    page number
     * @param pageSize  page size
     * @return resource list page
     */
    @Override
    public Result queryResourceListPaging(User loginUser, int directoryId, ResourceType type, String searchVal, Integer pageNo, Integer pageSize) {

        Result result = new Result();
        Page<Resource> page = new Page<>(pageNo, pageSize);
        int userId = loginUser.getId();
        if (isAdmin(loginUser)) {
            userId = 0;
        }
        if (directoryId != -1) {
            Resource directory = resourcesMapper.selectById(directoryId);
            if (directory == null) {
                putMsg(result, Status.RESOURCE_NOT_EXIST);
                return result;
            }
        }

        List<Integer> resourcesIds = resourceUserMapper.queryResourcesIdListByUserIdAndPerm(userId, 0);

        IPage<Resource> resourceIPage = resourcesMapper.queryResourcePaging(page, userId, directoryId, type.ordinal(), searchVal, resourcesIds);

        PageInfo<Resource> pageInfo = new PageInfo<>(pageNo, pageSize);
        pageInfo.setTotal((int) resourceIPage.getTotal());
        pageInfo.setTotalList(resourceIPage.getRecords());
        result.setData(pageInfo);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * create directory
     * xxx The steps to verify resources are cumbersome and can be optimized
     *
     * @param loginUser login user
     * @param fullName  full name
     * @param type      resource type
     * @param result    Result
     */
    private void createDirectory(User loginUser, String fullName, ResourceType type, Result<Object> result) {
        String tenantCode = tenantMapper.queryById(loginUser.getTenantId()).getTenantCode();
        String directoryName = storageOperate.getFileName(type, tenantCode, fullName);
        String resourceRootPath = storageOperate.getDir(type, tenantCode);
        try {
            if (!storageOperate.exists(tenantCode, resourceRootPath)) {
                storageOperate.createTenantDirIfNotExists(tenantCode);
            }

            if (!storageOperate.mkdir(tenantCode, directoryName)) {
                logger.error("create resource directory {}  failed", directoryName);
                putMsg(result, Status.STORE_OPERATE_CREATE_ERROR);
                throw new ServiceException(String.format("create resource directory: %s failed.", directoryName));
            }
        } catch (Exception e) {
            logger.error("create resource directory {}  failed", directoryName);
            putMsg(result, Status.STORE_OPERATE_CREATE_ERROR);
            throw new ServiceException(String.format("create resource directory: %s failed.", directoryName));
        }
    }

    /**
     * upload file to hdfs
     *
     * @param loginUser login user
     * @param fullName  full name
     * @param file      file
     */
    private boolean upload(User loginUser, String fullName, MultipartFile file, ResourceType type) {
        // save to local
        String fileSuffix = Files.getFileExtension(file.getOriginalFilename());
        String nameSuffix = Files.getFileExtension(fullName);

        // determine file suffix
        if (!fileSuffix.equalsIgnoreCase(nameSuffix)) {
            return false;
        }
        // query tenant
        String tenantCode = tenantMapper.queryById(loginUser.getTenantId()).getTenantCode();
        // random file name
        String localFilename = FileUtils.getUploadFilename(tenantCode, UUID.randomUUID().toString());

        // save file to hdfs, and delete original file
        String fileName = storageOperate.getFileName(type, tenantCode, fullName);
        String resourcePath = storageOperate.getDir(type, tenantCode);
        try {
            // if tenant dir not exists
            if (!storageOperate.exists(tenantCode, resourcePath)) {
                storageOperate.createTenantDirIfNotExists(tenantCode);
            }
            org.apache.dolphinscheduler.api.utils.FileUtils.copyInputStreamToFile(file, localFilename);
            storageOperate.upload(tenantCode, localFilename, fileName, true, true);
        } catch (Exception e) {
            FileUtils.deleteFile(localFilename);
            logger.error(e.getMessage(), e);
            return false;
        }
        return true;
    }

    /**
     * query resource list
     *
     * @param loginUser login user
     * @param type      resource type
     * @return resource list
     */
    @Override
    public Map<String, Object> queryResourceList(User loginUser, ResourceType type) {
        Map<String, Object> result = new HashMap<>();
        List<Resource> allResourceList = queryAuthoredResourceList(loginUser, type);
        Visitor resourceTreeVisitor = new ResourceTreeVisitor(allResourceList);
        result.put(Constants.DATA_LIST, resourceTreeVisitor.visit().getChildren());
        putMsg(result, Status.SUCCESS);

        return result;
    }

    /**
     * query resource list by program type
     *
     * @param loginUser login user
     * @param type      resource type
     * @return resource list
     */
    @Override
    public Map<String, Object> queryResourceByProgramType(User loginUser, ResourceType type, ProgramType programType) {
        Map<String, Object> result = new HashMap<>();

        List<Resource> allResourceList = queryAuthoredResourceList(loginUser, type);

        String suffix = ".jar";
        if (programType != null) {
            switch (programType) {
                case JAVA:
                case SCALA:
                    break;
                case PYTHON:
                    suffix = ".py";
                    break;
                default:
            }
        }
        List<Resource> resources = new ResourceFilter(suffix, new ArrayList<>(allResourceList)).filter();
        Visitor resourceTreeVisitor = new ResourceTreeVisitor(resources);
        result.put(Constants.DATA_LIST, resourceTreeVisitor.visit().getChildren());
        putMsg(result, Status.SUCCESS);

        return result;
    }

    /**
     * delete resource
     *
     * @param loginUser  login user
     * @param resourceId resource id
     * @return delete result code
     * @throws IOException exception
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> delete(User loginUser, int resourceId) throws IOException {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        // get resource by id
        Resource resource = resourcesMapper.selectById(resourceId);
        if (resource == null) {
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            return result;
        }
        if (!canOperator(loginUser, resource.getUserId())) {
            putMsg(result, Status.USER_NO_OPERATION_PERM);
            return result;
        }

        String tenantCode = getTenantCode(resource.getUserId(), result);
        if (StringUtils.isEmpty(tenantCode)) {
            return result;
        }

        // get all resource id of process definitions those is released
        List<Map<String, Object>> list = processDefinitionMapper.listResources();
        Map<Integer, Set<Long>> resourceProcessMap = ResourceProcessDefinitionUtils.getResourceProcessDefinitionMap(list);
        Set<Integer> resourceIdSet = resourceProcessMap.keySet();
        // get all children of the resource
        List<Integer> allChildren = listAllChildren(resource, true);
        Integer[] needDeleteResourceIdArray = allChildren.toArray(new Integer[allChildren.size()]);

        //if resource type is UDF,need check whether it is bound by UDF function
        if (resource.getType() == (ResourceType.UDF)) {
            List<UdfFunc> udfFuncs = udfFunctionMapper.listUdfByResourceId(needDeleteResourceIdArray);
            if (CollectionUtils.isNotEmpty(udfFuncs)) {
                logger.error("can't be deleted,because it is bound by UDF functions:{}", udfFuncs);
                putMsg(result, Status.UDF_RESOURCE_IS_BOUND, udfFuncs.get(0).getFuncName());
                return result;
            }
        }

        if (resourceIdSet.contains(resource.getPid())) {
            logger.error("can't be deleted,because it is used of process definition");
            putMsg(result, Status.RESOURCE_IS_USED);
            return result;
        }
        resourceIdSet.retainAll(allChildren);
        if (CollectionUtils.isNotEmpty(resourceIdSet)) {
            logger.error("can't be deleted,because it is used of process definition");
            for (Integer resId : resourceIdSet) {
                logger.error("resource id:{} is used of process definition {}",resId,resourceProcessMap.get(resId));
            }
            putMsg(result, Status.RESOURCE_IS_USED);
            return result;
        }

        // get hdfs file by type
        String storageFilename = storageOperate.getFileName(resource.getType(), tenantCode, resource.getFullName());
        //delete data in database
        resourcesMapper.selectBatchIds(Arrays.asList(needDeleteResourceIdArray)).forEach(item -> {
            updateParentResourceSize(item, item.getSize() * -1);
        });
        resourcesMapper.deleteIds(needDeleteResourceIdArray);
        resourceUserMapper.deleteResourceUserArray(0, needDeleteResourceIdArray);

        //delete file on hdfs

        //delete file on storage
        storageOperate.delete(tenantCode, storageFilename, true);
        putMsg(result, Status.SUCCESS);

        return result;
    }

    /**
     * verify resource by name and type
     *
     * @param loginUser login user
     * @param fullName  resource full name
     * @param type      resource type
     * @return true if the resource name not exists, otherwise return false
     */
    @Override
    public Result<Object> verifyResourceName(String fullName, ResourceType type, User loginUser) {
        Result<Object> result = new Result<>();
        putMsg(result, Status.SUCCESS);
        if (checkResourceExists(fullName, type.ordinal())) {
            logger.error("resource type:{} name:{} has exist, can't create again.", type, RegexUtils.escapeNRT(fullName));
            putMsg(result, Status.RESOURCE_EXIST);
        } else {
            // query tenant
            Tenant tenant = tenantMapper.queryById(loginUser.getTenantId());
            if (tenant != null) {
                String tenantCode = tenant.getTenantCode();
                try {
                    String filename = storageOperate.getFileName(type, tenantCode, fullName);
                    if (storageOperate.exists(tenantCode, filename)) {
                        putMsg(result, Status.RESOURCE_FILE_EXIST, filename);
                    }

                } catch (Exception e) {
                    logger.error("verify resource failed  and the reason is {}", e.getMessage());
                    putMsg(result, Status.STORE_OPERATE_CREATE_ERROR);
                }
            } else {
                putMsg(result, Status.CURRENT_LOGIN_USER_TENANT_NOT_EXIST);
            }
        }

        return result;
    }

    /**
     * verify resource by full name or pid and type
     *
     * @param fullName resource full name
     * @param id       resource id
     * @param type     resource type
     * @return true if the resource full name or pid not exists, otherwise return false
     */
    @Override
    public Result<Object> queryResource(String fullName, Integer id, ResourceType type) {
        Result<Object> result = new Result<>();
        if (StringUtils.isBlank(fullName) && id == null) {
            putMsg(result, Status.REQUEST_PARAMS_NOT_VALID_ERROR);
            return result;
        }
        if (StringUtils.isNotBlank(fullName)) {
            List<Resource> resourceList = resourcesMapper.queryResource(fullName, type.ordinal());
            if (CollectionUtils.isEmpty(resourceList)) {
                putMsg(result, Status.RESOURCE_NOT_EXIST);
                return result;
            }
            putMsg(result, Status.SUCCESS);
            result.setData(resourceList.get(0));
        } else {
            Resource resource = resourcesMapper.selectById(id);
            if (resource == null) {
                putMsg(result, Status.RESOURCE_NOT_EXIST);
                return result;
            }
            Resource parentResource = resourcesMapper.selectById(resource.getPid());
            if (parentResource == null) {
                putMsg(result, Status.RESOURCE_NOT_EXIST);
                return result;
            }
            putMsg(result, Status.SUCCESS);
            result.setData(parentResource);
        }
        return result;
    }

    /**
     * get resource by id
     * @param id        resource id
     * @return resource
     */
    @Override
    public Result<Object> queryResourceById(Integer id) {
        Result<Object> result = new Result<>();
        Resource resource = resourcesMapper.selectById(id);
        if (resource == null) {
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            return result;
        }
        putMsg(result, Status.SUCCESS);
        result.setData(resource);
        return result;
    }

    /**
     * view resource file online
     *
     * @param resourceId  resource id
     * @param skipLineNum skip line number
     * @param limit       limit
     * @return resource content
     */
    @Override
    public Result<Object> readResource(int resourceId, int skipLineNum, int limit) {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        // get resource by id
        Resource resource = resourcesMapper.selectById(resourceId);
        if (resource == null) {
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            return result;
        }
        //check preview or not by file suffix
        String nameSuffix = Files.getFileExtension(resource.getAlias());
        String resourceViewSuffixes = FileUtils.getResourceViewSuffixes();
        if (StringUtils.isNotEmpty(resourceViewSuffixes)) {
            List<String> strList = Arrays.asList(resourceViewSuffixes.split(","));
            if (!strList.contains(nameSuffix)) {
                logger.error("resource suffix {} not support view,  resource id {}", nameSuffix, resourceId);
                putMsg(result, Status.RESOURCE_SUFFIX_NOT_SUPPORT_VIEW);
                return result;
            }
        }

        String tenantCode = getTenantCode(resource.getUserId(), result);
        if (StringUtils.isEmpty(tenantCode)) {
            return result;
        }

        // source path
        String resourceFileName = storageOperate.getResourceFileName(tenantCode, resource.getFullName());
        logger.info("resource  path is {}", resourceFileName);
        try {
            if (storageOperate.exists(tenantCode, resourceFileName)) {
                List<String> content = storageOperate.vimFile(tenantCode, resourceFileName, skipLineNum, limit);

                putMsg(result, Status.SUCCESS);
                Map<String, Object> map = new HashMap<>();
                map.put(ALIAS, resource.getAlias());
                map.put(CONTENT, String.join("\n", content));
                result.setData(map);
            } else {
                logger.error("read file {} not exist in storage", resourceFileName);
                putMsg(result, Status.RESOURCE_FILE_NOT_EXIST, resourceFileName);
            }

        } catch (Exception e) {
            logger.error("Resource {} read failed", resourceFileName, e);
            putMsg(result, Status.HDFS_OPERATION_ERROR);
        }

        return result;
    }

    /**
     * create resource file online
     *
     * @param loginUser  login user
     * @param type       resource type
     * @param fileName   file name
     * @param fileSuffix file suffix
     * @param desc       description
     * @param content    content
     * @param pid        pid
     * @param currentDir current directory
     * @return create result code
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> onlineCreateResource(User loginUser, ResourceType type, String fileName, String fileSuffix, String desc, String content, int pid, String currentDir) {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        //check file suffix
        String nameSuffix = fileSuffix.trim();
        String resourceViewSuffixes = FileUtils.getResourceViewSuffixes();
        if (StringUtils.isNotEmpty(resourceViewSuffixes)) {
            List<String> strList = Arrays.asList(resourceViewSuffixes.split(","));
            if (!strList.contains(nameSuffix)) {
                logger.error("resource suffix {} not support create", nameSuffix);
                putMsg(result, Status.RESOURCE_SUFFIX_NOT_SUPPORT_VIEW);
                return result;
            }
        }

        String name = fileName.trim() + "." + nameSuffix;
        String fullName = getFullName(currentDir, name);
        result = verifyResource(loginUser, type, fullName, pid);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        // save data
        Date now = new Date();
        Resource resource = new Resource(pid, name, fullName, false, desc, name, loginUser.getId(), type, content.getBytes().length, now, now);

        resourcesMapper.insert(resource);
        updateParentResourceSize(resource, resource.getSize());

        putMsg(result, Status.SUCCESS);
        Map<String, Object> resultMap = new HashMap<>();
        for (Map.Entry<Object, Object> entry : new BeanMap(resource).entrySet()) {
            if (!Constants.CLASS.equalsIgnoreCase(entry.getKey().toString())) {
                resultMap.put(entry.getKey().toString(), entry.getValue());
            }
        }
        result.setData(resultMap);

        String tenantCode = tenantMapper.queryById(loginUser.getTenantId()).getTenantCode();

        result = uploadContentToStorage(fullName, tenantCode, content);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            throw new ServiceException(result.getMsg());
        }
        return result;
    }

    private Result<Object> checkResourceUploadStartupState() {
        Result<Object> result = new Result<>();
        putMsg(result, Status.SUCCESS);
        // if resource upload startup
        if (!PropertyUtils.getResUploadStartupState()) {
            logger.error("resource upload startup state: {}", PropertyUtils.getResUploadStartupState());
            putMsg(result, Status.STORAGE_NOT_STARTUP);
            return result;
        }
        return result;
    }

    private Result<Object> verifyResource(User loginUser, ResourceType type, String fullName, int pid) {
        Result<Object> result = verifyResourceName(fullName, type, loginUser);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }
        return verifyPid(loginUser, pid);
    }

    private Result<Object> verifyPid(User loginUser, int pid) {
        Result<Object> result = new Result<>();
        putMsg(result, Status.SUCCESS);
        if (pid != -1) {
            Resource parentResource = resourcesMapper.selectById(pid);
            if (parentResource == null) {
                putMsg(result, Status.PARENT_RESOURCE_NOT_EXIST);
                return result;
            }
            if (!canOperator(loginUser, parentResource.getUserId())) {
                putMsg(result, Status.USER_NO_OPERATION_PERM);
                return result;
            }
        }
        return result;
    }

    /**
     * updateProcessInstance resource
     *
     * @param resourceId resource id
     * @param content    content
     * @return update result cod
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> updateResourceContent(int resourceId, String content) {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        Resource resource = resourcesMapper.selectById(resourceId);
        if (resource == null) {
            logger.error("read file not exist,  resource id {}", resourceId);
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            return result;
        }
        //check can edit by file suffix
        String nameSuffix = Files.getFileExtension(resource.getAlias());
        String resourceViewSuffixes = FileUtils.getResourceViewSuffixes();
        if (StringUtils.isNotEmpty(resourceViewSuffixes)) {
            List<String> strList = Arrays.asList(resourceViewSuffixes.split(","));
            if (!strList.contains(nameSuffix)) {
                logger.error("resource suffix {} not support updateProcessInstance,  resource id {}", nameSuffix, resourceId);
                putMsg(result, Status.RESOURCE_SUFFIX_NOT_SUPPORT_VIEW);
                return result;
            }
        }

        String tenantCode = getTenantCode(resource.getUserId(), result);
        if (StringUtils.isEmpty(tenantCode)) {
            return result;
        }
        long originFileSize = resource.getSize();
        resource.setSize(content.getBytes().length);
        resource.setUpdateTime(new Date());
        resourcesMapper.updateById(resource);

        result = uploadContentToStorage(resource.getFullName(), tenantCode, content);
        updateParentResourceSize(resource, resource.getSize() - originFileSize);

        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            throw new ServiceException(result.getMsg());
        }
        return result;
    }

    /**
     * @param resourceName resource name
     * @param tenantCode   tenant code
     * @param content      content
     * @return result
     */
    private Result<Object> uploadContentToStorage(String resourceName, String tenantCode, String content) {
        Result<Object> result = new Result<>();
        String localFilename = "";
        String storageFileName = "";
        try {
            localFilename = FileUtils.getUploadFilename(tenantCode, UUID.randomUUID().toString());

            if (!FileUtils.writeContent2File(content, localFilename)) {
                // write file fail
                logger.error("file {} fail, content is {}", localFilename, RegexUtils.escapeNRT(content));
                putMsg(result, Status.RESOURCE_NOT_EXIST);
                return result;
            }

            // get resource file  path
            storageFileName = storageOperate.getResourceFileName(tenantCode, resourceName);
            String resourcePath = storageOperate.getResDir(tenantCode);
            logger.info("resource  path is {}, resource dir is {}", storageFileName, resourcePath);


            if (!storageOperate.exists(tenantCode, resourcePath)) {
                // create if tenant dir not exists
                storageOperate.createTenantDirIfNotExists(tenantCode);
            }
            if (storageOperate.exists(tenantCode, storageFileName)) {
                storageOperate.delete(tenantCode, storageFileName, false);
            }

            storageOperate.upload(tenantCode, localFilename, storageFileName, true, true);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.setCode(Status.HDFS_OPERATION_ERROR.getCode());
            result.setMsg(String.format("copy %s to hdfs %s fail", localFilename, storageFileName));
            return result;
        }
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * download file
     *
     * @param resourceId resource id
     * @return resource content
     * @throws IOException exception
     */
    @Override
    public org.springframework.core.io.Resource downloadResource(int resourceId) throws IOException {
        // if resource upload startup
        if (!PropertyUtils.getResUploadStartupState()) {
            logger.error("resource upload startup state: {}", PropertyUtils.getResUploadStartupState());
            throw new ServiceException("hdfs not startup");
        }

        Resource resource = resourcesMapper.selectById(resourceId);
        if (resource == null) {
            logger.error("download file not exist,  resource id {}", resourceId);
            return null;
        }
        if (resource.isDirectory()) {
            logger.error("resource id {} is directory,can't download it", resourceId);
            throw new ServiceException("can't download directory");
        }

        int userId = resource.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            logger.error("user id {} not exists", userId);
            throw new ServiceException(String.format("resource owner id %d not exist", userId));
        }

        Tenant tenant = tenantMapper.queryById(user.getTenantId());
        if (tenant == null) {
            logger.error("tenant id {} not exists", user.getTenantId());
            throw new ServiceException(String.format("The tenant id %d of resource owner not exist", user.getTenantId()));
        }

        String tenantCode = tenant.getTenantCode();

        String fileName = storageOperate.getFileName(resource.getType(), tenantCode, resource.getFullName());

        String localFileName = FileUtils.getDownloadFilename(resource.getAlias());
        logger.info("resource  path is {}, download local filename is {}", fileName, localFileName);

        try {
            storageOperate.download(tenantCode, fileName, localFileName, false, true);
            return org.apache.dolphinscheduler.api.utils.FileUtils.file2Resource(localFileName);
        } catch (IOException e) {
            logger.error("download resource error, the path is {}, and local filename is {}, the error message is {}", fileName, localFileName, e.getMessage());
            throw new ServerException("download the resource file failed ,it may be related to your storage");
        }


    }

    /**
     * list all file
     *
     * @param loginUser login user
     * @param userId    user id
     * @return unauthorized result code
     */
    @Override
    public Map<String, Object> authorizeResourceTree(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();

        List<Resource> resourceList;
        if (isAdmin(loginUser)) {
            // admin gets all resources except userId
            resourceList = resourcesMapper.queryResourceExceptUserId(userId);
        } else {
            // non-admins users get their own resources
            resourceList = resourcesMapper.queryResourceListAuthored(loginUser.getId(), -1);
        }
        List<ResourceComponent> list;
        if (CollectionUtils.isNotEmpty(resourceList)) {
            Visitor visitor = new ResourceTreeVisitor(resourceList);
            list = visitor.visit().getChildren();
        } else {
            list = new ArrayList<>(0);
        }

        result.put(Constants.DATA_LIST, list);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * unauthorized file
     *
     * @param loginUser login user
     * @param userId    user id
     * @return unauthorized result code
     */
    @Override
    public Map<String, Object> unauthorizedFile(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();

        List<Resource> resourceList;
        if (isAdmin(loginUser)) {
            // admin gets all resources except userId
            resourceList = resourcesMapper.queryResourceExceptUserId(userId);
        } else {
            // non-admins users get their own resources
            resourceList = resourcesMapper.queryResourceListAuthored(loginUser.getId(), -1);
        }
        List<Resource> list;
        if (resourceList != null && !resourceList.isEmpty()) {
            Set<Resource> resourceSet = new HashSet<>(resourceList);
            List<Resource> authedResourceList = queryResourceList(userId, Constants.AUTHORIZE_WRITABLE_PERM);
            getAuthorizedResourceList(resourceSet, authedResourceList);
            list = new ArrayList<>(resourceSet);
        } else {
            list = new ArrayList<>(0);
        }
        Visitor visitor = new ResourceTreeVisitor(list);
        result.put(Constants.DATA_LIST, visitor.visit().getChildren());
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * unauthorized udf function
     *
     * @param loginUser login user
     * @param userId    user id
     * @return unauthorized result code
     */
    @Override
    public Map<String, Object> unauthorizedUDFFunction(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();

        List<UdfFunc> udfFuncList;
        if (isAdmin(loginUser)) {
            // admin gets all udfs except userId
            udfFuncList = udfFunctionMapper.queryUdfFuncExceptUserId(userId);
        } else {
            // non-admins users get their own udfs
            udfFuncList = udfFunctionMapper.selectByMap(Collections.singletonMap("user_id", loginUser.getId()));
        }
        List<UdfFunc> resultList = new ArrayList<>();
        Set<UdfFunc> udfFuncSet;
        if (CollectionUtils.isNotEmpty(udfFuncList)) {
            udfFuncSet = new HashSet<>(udfFuncList);

            List<UdfFunc> authedUDFFuncList = udfFunctionMapper.queryAuthedUdfFunc(userId);

            getAuthorizedResourceList(udfFuncSet, authedUDFFuncList);
            resultList = new ArrayList<>(udfFuncSet);
        }
        result.put(Constants.DATA_LIST, resultList);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * authorized udf function
     *
     * @param loginUser login user
     * @param userId    user id
     * @return authorized result code
     */
    @Override
    public Map<String, Object> authorizedUDFFunction(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();

        List<UdfFunc> udfFuncs = udfFunctionMapper.queryAuthedUdfFunc(userId);
        result.put(Constants.DATA_LIST, udfFuncs);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * authorized file
     *
     * @param loginUser login user
     * @param userId    user id
     * @return authorized result
     */
    @Override
    public Map<String, Object> authorizedFile(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();

        List<Resource> authedResources = queryResourceList(userId, Constants.AUTHORIZE_WRITABLE_PERM);
        Visitor visitor = new ResourceTreeVisitor(authedResources);
        String visit = JSONUtils.toJsonString(visitor.visit(), SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        logger.info(visit);
        String jsonTreeStr = JSONUtils.toJsonString(visitor.visit().getChildren(), SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        logger.info(jsonTreeStr);
        result.put(Constants.DATA_LIST, visitor.visit().getChildren());
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * get authorized resource list
     *
     * @param resourceSet        resource set
     * @param authedResourceList authorized resource list
     */
    private void getAuthorizedResourceList(Set<?> resourceSet, List<?> authedResourceList) {
        Set<?> authedResourceSet;
        if (CollectionUtils.isNotEmpty(authedResourceList)) {
            authedResourceSet = new HashSet<>(authedResourceList);
            resourceSet.removeAll(authedResourceSet);
        }
    }

    /**
     * get tenantCode by UserId
     *
     * @param userId user id
     * @param result return result
     * @return tenant code
     */
    private String getTenantCode(int userId, Result<Object> result) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            logger.error("user {} not exists", userId);
            putMsg(result, Status.USER_NOT_EXIST, userId);
            return null;
        }

        Tenant tenant = tenantMapper.queryById(user.getTenantId());
        if (tenant == null) {
            logger.error("tenant not exists");
            putMsg(result, Status.CURRENT_LOGIN_USER_TENANT_NOT_EXIST);
            return null;
        }
        return tenant.getTenantCode();
    }

    /**
     * list all children id
     *
     * @param resource    resource
     * @param containSelf whether add self to children list
     * @return all children id
     */
    List<Integer> listAllChildren(Resource resource, boolean containSelf) {
        List<Integer> childList = new ArrayList<>();
        if (resource.getId() != -1 && containSelf) {
            childList.add(resource.getId());
        }

        if (resource.isDirectory()) {
            listAllChildren(resource.getId(), childList);
        }
        return childList;
    }

    /**
     * list all children id
     *
     * @param resourceId resource id
     * @param childList  child list
     */
    void listAllChildren(int resourceId, List<Integer> childList) {
        List<Integer> children = resourcesMapper.listChildren(resourceId);
        for (int childId : children) {
            childList.add(childId);
            listAllChildren(childId, childList);
        }
    }

    /**
     * query authored resource list (own and authorized)
     *
     * @param loginUser login user
     * @param type      ResourceType
     * @return all authored resource list
     */
    private List<Resource> queryAuthoredResourceList(User loginUser, ResourceType type) {
        List<Resource> relationResources;
        int userId = loginUser.getId();
        if (isAdmin(loginUser)) {
            userId = 0;
            relationResources = new ArrayList<>();
        } else {
            // query resource relation
            relationResources = queryResourceList(userId, 0);
        }
        // filter by resource type
        List<Resource> relationTypeResources =
                relationResources.stream().filter(rs -> rs.getType() == type).collect(Collectors.toList());

        List<Resource> ownResourceList = resourcesMapper.queryResourceListAuthored(userId, type.ordinal());
        ownResourceList.addAll(relationTypeResources);

        return ownResourceList;
    }

    /**
     * query resource list by userId and perm
     *
     * @param userId userId
     * @param perm   perm
     * @return resource list
     */
    private List<Resource> queryResourceList(Integer userId, int perm) {
        List<Integer> resIds = resourceUserMapper.queryResourcesIdListByUserIdAndPerm(userId, perm);
        return CollectionUtils.isEmpty(resIds) ? new ArrayList<>() : resourcesMapper.queryResourceListById(resIds);
    }

}
