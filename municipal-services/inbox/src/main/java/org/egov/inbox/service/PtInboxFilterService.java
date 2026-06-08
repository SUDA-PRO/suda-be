package org.egov.inbox.service;

import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.inbox.config.InboxConfiguration;
import org.egov.inbox.repository.ServiceRequestRepository;
import org.egov.inbox.web.model.InboxSearchCriteria;
import org.egov.inbox.web.model.RequestInfoWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.egov.inbox.util.PTConstants.*;
import static org.egov.inbox.util.PTConstants.LIMIT_PARAM;

@Slf4j
@Service
public class PtInboxFilterService {

    @Value("${egov.user.host}")
    private String userHost;

    @Value("${egov.user.search.path}")
    private String userSearchEndpoint;

    @Value("${egov.searcher.host}")
    private String searcherHost;

    @Value("${egov.searcher.pt.search.path}")
    private String ptInboxSearcherEndpoint;

    @Value("${egov.searcher.pt.search.desc.path}")
    private String ptInboxSearcherDescEndpoint;

    @Value("${egov.searcher.pt.count.path}")
    private String ptInboxSearcherCountEndpoint;

    // PT service search path is read from service.search.mapping (same as fetchModuleObjects)
    // so it uses the same correctly-configured Kubernetes URL — no extra @Value needed.
    private static final String PT_BUSINESS_SERVICE_KEY = "PT.CREATE,PT.MUTATION,PT.UPDATE";

    @Autowired
    private InboxConfiguration config;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    /**
     * Fetches acknowledgement IDs directly from property-services/_search.
     * Uses the same searchPath as fetchModuleObjects (from service.search.mapping)
     * so the URL is guaranteed correct in every environment (local, dev, prod).
     * isInboxSearch=true bypasses mandatory-criteria validation and also ensures
     * applications with null propertyId (pre-approval) are NOT filtered out.
     */
    public List<String> fetchAcknowledgementIdsFromSearcher(InboxSearchCriteria criteria, HashMap<String, String> StatusIdNameMap, RequestInfo requestInfo){
        List<String> acknowledgementNumbers = new ArrayList<>();
        HashMap moduleSearchCriteria = criteria.getModuleSearchCriteria();

        Boolean isMobileNumberPresent = moduleSearchCriteria.containsKey(MOBILE_NUMBER_PARAM);
        List<String> userUUIDs = new ArrayList<>();
        if(isMobileNumberPresent) {
            String tenantId = criteria.getTenantId();
            String mobileNumber = String.valueOf(moduleSearchCriteria.get(MOBILE_NUMBER_PARAM));
            userUUIDs = fetchUserUUID(mobileNumber, requestInfo, tenantId);
            if(CollectionUtils.isEmpty(userUUIDs)){
                return new ArrayList<>();
            }
        }

        // Use the same searchPath configured in service.search.mapping for PT
        // (this is the URL already working in fetchModuleObjects)
        String ptSearchPath = config.getServiceSearchMapping()
                .get(PT_BUSINESS_SERVICE_KEY).get("searchPath");

        StringBuilder uri = new StringBuilder(ptSearchPath);
        uri.append("?tenantId=").append(criteria.getTenantId());
        uri.append("&isInboxSearch=true");
        uri.append("&creationReason=CREATE&creationReason=MUTATION&creationReason=UPDATE");
        uri.append("&status=INWORKFLOW");

        if(moduleSearchCriteria.containsKey(PROPERTY_ID_PARAM)){
            uri.append("&propertyIds=").append(moduleSearchCriteria.get(PROPERTY_ID_PARAM));
        }
        if(moduleSearchCriteria.containsKey(PT_APPLICATION_NUMBER_PARAM)){
            uri.append("&acknowledgementIds=").append(moduleSearchCriteria.get(PT_APPLICATION_NUMBER_PARAM));
        }
        if(moduleSearchCriteria.containsKey(LOCALITY_PARAM)){
            uri.append("&locality=").append(moduleSearchCriteria.get(LOCALITY_PARAM));
        }
        if(!CollectionUtils.isEmpty(userUUIDs)){
            userUUIDs.forEach(uuid -> uri.append("&ownerIds=").append(uuid));
        }

        uri.append("&limit=").append(criteria.getLimit());
        uri.append("&offset=").append(criteria.getOffset());

        moduleSearchCriteria.put(LIMIT_PARAM, criteria.getLimit());

        log.info("PT inbox acknowledgement IDs fetch URL: {}", uri);

        RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
        try {
            Object result = serviceRequestRepository.fetchResult(uri, requestInfoWrapper);
            if(result != null){
                acknowledgementNumbers = JsonPath.read(result, "$.Properties[*].acknowldgementNumber");
            }
        } catch (Exception e) {
            log.error("Error fetching PT acknowledgement IDs from property-services: {}", e.getMessage());
        }
        return acknowledgementNumbers;
    }

    public Integer fetchAcknowledgementIdsCountFromSearcher(InboxSearchCriteria criteria, HashMap<String, String> StatusIdNameMap, RequestInfo requestInfo){
        HashMap moduleSearchCriteria = criteria.getModuleSearchCriteria();

        Boolean isMobileNumberPresent = moduleSearchCriteria.containsKey(MOBILE_NUMBER_PARAM);
        List<String> userUUIDs = new ArrayList<>();
        if(isMobileNumberPresent) {
            String tenantId = criteria.getTenantId();
            String mobileNumber = String.valueOf(moduleSearchCriteria.get(MOBILE_NUMBER_PARAM));
            userUUIDs = fetchUserUUID(mobileNumber, requestInfo, tenantId);
            if(CollectionUtils.isEmpty(userUUIDs)){
                return 0;
            }
        }

        String ptSearchPath = config.getServiceSearchMapping()
                .get(PT_BUSINESS_SERVICE_KEY).get("searchPath");

        StringBuilder uri = new StringBuilder(ptSearchPath);
        uri.append("?tenantId=").append(criteria.getTenantId());
        uri.append("&isInboxSearch=true");
        uri.append("&isRequestForCount=true");
        uri.append("&creationReason=CREATE&creationReason=MUTATION&creationReason=UPDATE");
        uri.append("&status=INWORKFLOW");

        if(moduleSearchCriteria.containsKey(PROPERTY_ID_PARAM)){
            uri.append("&propertyIds=").append(moduleSearchCriteria.get(PROPERTY_ID_PARAM));
        }
        if(moduleSearchCriteria.containsKey(PT_APPLICATION_NUMBER_PARAM)){
            uri.append("&acknowledgementIds=").append(moduleSearchCriteria.get(PT_APPLICATION_NUMBER_PARAM));
        }
        if(moduleSearchCriteria.containsKey(LOCALITY_PARAM)){
            uri.append("&locality=").append(moduleSearchCriteria.get(LOCALITY_PARAM));
        }
        if(!CollectionUtils.isEmpty(userUUIDs)){
            userUUIDs.forEach(uuid -> uri.append("&ownerIds=").append(uuid));
        }

        log.info("PT inbox count fetch URL: {}", uri);

        RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
        try {
            Object result = serviceRequestRepository.fetchResult(uri, requestInfoWrapper);
            if(result != null){
                Object countVal = JsonPath.read(result, "$.count");
                if(countVal != null) return ((Number) countVal).intValue();
            }
        } catch (Exception e) {
            log.error("Error fetching PT count from property-services: {}", e.getMessage());
        }
        return 0;
    }


    private List<String> fetchUserUUID(String mobileNumber, RequestInfo requestInfo, String tenantId) {
        StringBuilder uri = new StringBuilder();
        uri.append(userHost).append(userSearchEndpoint);
        Map<String, Object> userSearchRequest = new HashMap<>();
        userSearchRequest.put("RequestInfo", requestInfo);
        userSearchRequest.put("tenantId", tenantId);
        userSearchRequest.put("userType", "CITIZEN");
        userSearchRequest.put("mobileNumber", mobileNumber);
        List<String> userUuids = new ArrayList<>();
        try {
            Object user = serviceRequestRepository.fetchResult(uri, userSearchRequest);
            if(null != user) {
                //log.info(user.toString());
                userUuids = JsonPath.read(user, "$.user.*.uuid");
            }else {
                log.error("Service returned null while fetching user for mobile number - " + mobileNumber);
            }
        }catch(Exception e) {
            log.error("Exception while fetching user for mobile number - " + mobileNumber);
            log.error("Exception trace: ", e);
        }
        return userUuids;
    }
}
