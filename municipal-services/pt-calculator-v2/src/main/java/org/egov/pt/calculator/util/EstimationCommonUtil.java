package org.egov.pt.calculator.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pt.calculator.service.BillingSlabService;
import org.egov.pt.calculator.service.DemandService;
import org.egov.pt.calculator.service.MasterDataService;
import org.egov.pt.calculator.service.PayService;
import org.egov.pt.calculator.web.models.*;
import org.egov.pt.calculator.web.models.demand.Category;
import org.egov.pt.calculator.web.models.demand.Demand;
import org.egov.pt.calculator.web.models.demand.TaxHeadMaster;
import org.egov.pt.calculator.web.models.property.OwnerInfo;
import org.egov.pt.calculator.web.models.property.Property;
import org.egov.pt.calculator.web.models.property.PropertyDetail;
import org.egov.pt.calculator.web.models.property.Unit;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.egov.pt.calculator.util.CalculatorConstants.*;

/**
 * EstimationCommonUtil - Contains common utility methods for estimation calculations
 * This class holds shared logic across all estimation strategy implementations
 *
 * @author PT Calculator Service
 * @version 1.0
 */
@Component
@Slf4j
public class EstimationCommonUtil {

    @Autowired
    private BillingSlabService billingSlabService;

    @Autowired
    private Configurations configs;

    @Autowired
    private MasterDataService mDataService;

    @Autowired
    private PayService payService;

    @Autowired
    private DemandService demandService;

    @Autowired
    private CalculatorUtils utils;

    public List<BillingSlab> getCommonSlabsFirstLevelFiltered(Property property, String financialYear, RequestInfo requestInfo) {
        PropertyDetail detail = property.getPropertyDetails().get(0);
        log.debug("Financial Year in Criteria: {}", financialYear);

        List<BillingSlab> billingSlabs = getCommonBillingSlabs(property, financialYear, requestInfo);
        final String all = configs.getSlabValueAll();

        Double plotSize = null != detail.getLandArea() ? detail.getLandArea() : detail.getBuildUpArea();

        final String dtlPtType = detail.getPropertyType();
        final String dtlPtSubType = detail.getPropertySubType();
        final String dtlOwnerShipCat = detail.getOwnershipCategory();
        final String dtlSubOwnerShipCat = detail.getSubOwnershipCategory();
        final String dtlAreaType = property.getAddress().getLocality().getArea();
        final Boolean dtlIsMultiFloored = detail.getNoOfFloors() > 1;

        return billingSlabs.stream().filter(slab -> {
            Boolean slabMultiFloored = slab.getIsPropertyMultiFloored();
            String slabAreaType = slab.getAreaType();
            String slabPropertyType = slab.getPropertyType();
            String slabPropertySubType = slab.getPropertySubType();
            String slabOwnerShipCat = slab.getOwnerShipCategory();
            String slabSubOwnerShipCat = slab.getSubOwnerShipCategory();
            Double slabAreaFrom = slab.getFromPlotSize();
            Double slabAreaTo = slab.getToPlotSize();

            boolean isPropertyMultiFloored = slabMultiFloored.equals(dtlIsMultiFloored);
            boolean isAreaMatching = slabAreaType.equalsIgnoreCase(dtlAreaType) || all.equalsIgnoreCase(slab.getAreaType());
            boolean isPtTypeMatching = slabPropertyType.equalsIgnoreCase(dtlPtType);
            boolean isPtSubTypeMatching = slabPropertySubType.equalsIgnoreCase(dtlPtSubType)
                    || all.equalsIgnoreCase(slabPropertySubType);
            boolean isOwnerShipMatching = slabOwnerShipCat.equalsIgnoreCase(dtlOwnerShipCat)
                    || all.equalsIgnoreCase(slabOwnerShipCat);
            boolean isSubOwnerShipMatching = slabSubOwnerShipCat.equalsIgnoreCase(dtlSubOwnerShipCat)
                    || all.equalsIgnoreCase(slabSubOwnerShipCat);
            boolean isPlotMatching = false;

            if (plotSize == 0.0)
                isPlotMatching = slabAreaFrom <= plotSize && slabAreaTo >= plotSize;
            else
                isPlotMatching = slabAreaFrom < plotSize && slabAreaTo >= plotSize;

            return isPtTypeMatching && isPtSubTypeMatching && isOwnerShipMatching && isSubOwnerShipMatching
                    && isPlotMatching && isAreaMatching && isPropertyMultiFloored;
        }).collect(Collectors.toList());
    }

    public List<BillingSlab> getCommonBillingSlabs(Property property, String financialYear, RequestInfo requestInfo) {
        String tenantId = property.getTenantId();
        String validFrom = financialYear.split("-")[0] + "-04-01";
        String validTo = "20" + financialYear.split("-")[1] + "-03-31";
        BillingSlabSearchCriteria slabSearchCriteria = BillingSlabSearchCriteria.builder().tenantId(tenantId).validFrom(validFrom).validTo(validTo).build();
        List<BillingSlab> billingSlabs = billingSlabService.searchBillingSlabs(requestInfo, slabSearchCriteria)
                .getBillingSlab();

        log.debug("Slabs count: {}", billingSlabs.size());
        return billingSlabs;
    }

    public BigDecimal getCommonExemption(Unit unit, BigDecimal currentUnitTax, String financialYear,
                                         Map<String, Map<String, List<Object>>> propertyMasterMap) {
        Map<String, Object> exemption = getCommonExemptionFromUsage(unit, financialYear, propertyMasterMap);
        return mDataService.calculateApplicables(currentUnitTax, exemption);
    }

    public Map<String, Object> getCommonExemptionFromUsage(Unit unit, String financialYear,
                                                           Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap) {

        Map<String, List<Object>> usageDetails = propertyBasedExemptionMasterMap.get(USAGE_DETAIL_MASTER);
        Map<String, List<Object>> usageSubMinors = propertyBasedExemptionMasterMap.get(USAGE_SUB_MINOR_MASTER);
        Map<String, List<Object>> usageMinors = propertyBasedExemptionMasterMap.get(USAGE_MINOR_MASTER);
        Map<String, List<Object>> usageMajors = propertyBasedExemptionMasterMap.get(USAGE_MAJOR_MASTER);

        Map<String, Object> applicableUsageMasterExemption = null;

        if (null != usageDetails.get(unit.getUsageCategoryDetail())) {
            applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
                    usageDetails.get(unit.getUsageCategoryDetail()));
        }

        if (isExemptionNull(applicableUsageMasterExemption)
                && null != usageSubMinors.get(unit.getUsageCategorySubMinor())) {
            applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
                    usageSubMinors.get(unit.getUsageCategorySubMinor()));
        }

        if (isExemptionNull(applicableUsageMasterExemption) && null != usageMinors.get(unit.getUsageCategoryMinor())) {
            applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
                    usageMinors.get(unit.getUsageCategoryMinor()));
        }

        if (isExemptionNull(applicableUsageMasterExemption) && null != usageMajors.get(unit.getUsageCategoryMajor())) {
            applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
                    usageMajors.get(unit.getUsageCategoryMajor()));
        }

        if (null != applicableUsageMasterExemption) {
            applicableUsageMasterExemption = (Map<String, Object>) applicableUsageMasterExemption.get(EXEMPTION_FIELD_NAME);
        }

        return applicableUsageMasterExemption;
    }

    private boolean isExemptionNull(Map<String, Object> applicableUsageMasterExemption) {
        return !(null != applicableUsageMasterExemption
                && null != applicableUsageMasterExemption.get(EXEMPTION_FIELD_NAME));
    }

    public BigDecimal getCommonOwnerExemption(Set<OwnerInfo> owners, BigDecimal taxAmt, String financialYear,
                                              Map<String, Map<String, List<Object>>> propertyMasterMap) {
        Map<String, List<Object>> ownerTypeMap = propertyMasterMap.get(OWNER_TYPE_MASTER);
        BigDecimal userExemption = BigDecimal.ZERO;
        final int userCount = owners.size();
        BigDecimal share = taxAmt.divide(BigDecimal.valueOf(userCount), 2, 2);

        for (OwnerInfo owner : owners) {
            if (null == ownerTypeMap.get(owner.getOwnerType())) {
                continue;
            }

            Map<String, Object> applicableOwnerType = mDataService.getApplicableMaster(financialYear,
                    ownerTypeMap.get(owner.getOwnerType()));

            if (null != applicableOwnerType) {
                BigDecimal currentExemption = mDataService.calculateApplicables(share,
                        applicableOwnerType.get(EXEMPTION_FIELD_NAME));
                userExemption = userExemption.add(currentExemption);
            }
        }
        return userExemption;
    }

    public BigDecimal getCommonUnBuiltRate(PropertyDetail detail, double unBuiltRate, int groundUnitsCount, Double groundUnitsArea) {
        BigDecimal unBuiltAmt = BigDecimal.ZERO;
        if (0.0 < unBuiltRate && null != detail.getLandArea() && groundUnitsCount > 0) {
            double diffArea = null != detail.getBuildUpArea() ? detail.getLandArea() - detail.getBuildUpArea()
                    : detail.getLandArea() - groundUnitsArea;
            diffArea = diffArea < 0.0 ? 0.0 : diffArea;
            unBuiltAmt = unBuiltAmt.add(BigDecimal.valueOf((unBuiltRate / groundUnitsCount) * (diffArea)));
        }
        return unBuiltAmt;
    }

    public BigDecimal getCommonTaxForUnit(BillingSlab slab, Unit unit) {
        boolean isUnitCommercial = unit.getUsageCategoryMajor().equalsIgnoreCase(configs.getUsageMajorNonResidential());
        boolean isUnitRented = unit.getOccupancyType().equalsIgnoreCase(configs.getOccupancyTypeRented());
        BigDecimal currentUnitTax;

        if (null == slab) {
            String msg = BILLING_SLAB_MATCH_ERROR_MESSAGE
                    .replace(BILLING_SLAB_MATCH_AREA, unit.getUnitArea().toString())
                    .replace(BILLING_SLAB_MATCH_FLOOR, unit.getFloorNo())
                    .replace(BILLING_SLAB_MATCH_USAGE_DETAIL,
                            null != unit.getUsageCategoryDetail() ? unit.getUsageCategoryDetail() : "nill");
            throw new CustomException(BILLING_SLAB_MATCH_ERROR_CODE, msg);
        }

        if (isUnitCommercial && isUnitRented) {
            if (unit.getArv() == null) {
                throw new CustomException(EG_PT_ESTIMATE_ARV_NULL, EG_PT_ESTIMATE_ARV_NULL_MSG);
            }

            BigDecimal multiplier;
            if (null != slab.getArvPercent()) {
                multiplier = BigDecimal.valueOf(slab.getArvPercent() / 100);
            } else {
                multiplier = BigDecimal.valueOf(configs.getArvPercent() / 100);
            }
            currentUnitTax = unit.getArv().multiply(multiplier);
        } else {
            currentUnitTax = BigDecimal.valueOf(unit.getUnitArea() * slab.getUnitRate());
        }
        return currentUnitTax;
    }

    public BillingSlab getCommonUniqueSlabSecondLevelFiltered(List<BillingSlab> billingSlabs, Unit unit) {
        final String all = configs.getSlabValueAll();
        List<BillingSlab> matchingList = new ArrayList<>();

        for (BillingSlab billSlb : billingSlabs) {
            Double floorNo = Double.parseDouble(unit.getFloorNo());

            boolean isMajorMatching = billSlb.getUsageCategoryMajor().equalsIgnoreCase(unit.getUsageCategoryMajor())
                    || (billSlb.getUsageCategoryMajor().equalsIgnoreCase(all));
            boolean isMinorMatching = billSlb.getUsageCategoryMinor().equalsIgnoreCase(unit.getUsageCategoryMinor())
                    || (billSlb.getUsageCategoryMinor().equalsIgnoreCase(all));
            boolean isSubMinorMatching = billSlb.getUsageCategorySubMinor().equalsIgnoreCase(
                    unit.getUsageCategorySubMinor()) || (billSlb.getUsageCategorySubMinor().equalsIgnoreCase(all));
            boolean isDetailsMatching = billSlb.getUsageCategoryDetail().equalsIgnoreCase(unit.getUsageCategoryDetail())
                    || (billSlb.getUsageCategoryDetail().equalsIgnoreCase(all));
            boolean isFloorMatching = billSlb.getFromFloor() <= floorNo && billSlb.getToFloor() >= floorNo;
            boolean isOccupancyTypeMatching = billSlb.getOccupancyType().equalsIgnoreCase(unit.getOccupancyType())
                    || (billSlb.getOccupancyType().equalsIgnoreCase(all));

            if (isMajorMatching && isMinorMatching && isSubMinorMatching && isDetailsMatching && isFloorMatching
                    && isOccupancyTypeMatching) {
                matchingList.add(billSlb);
                log.debug("Matching slab ID: {}", billSlb.getId());
            }
        }
        if (matchingList.size() == 1) {
            return matchingList.get(0);
        } else if (matchingList.isEmpty()) {
            return null;
        } else {
            throw new CustomException(PT_ESTIMATE_BILLINGSLABS_UNMATCH, PT_ESTIMATE_BILLINGSLABS_UNMATCH_MSG
                    .replace(PT_ESTIMATE_BILLINGSLABS_UNMATCH_replace_id, matchingList.toString()) + unit);
        }
    }

    public Calculation getCalculation(RequestInfo requestInfo, CalculationCriteria criteria, Map<String, Object> masterMap, List<TaxHeadEstimate> estimates, List<String> billingSlabIds) {
        Property property = criteria.getProperty();
        PropertyDetail detail = property.getPropertyDetails().get(0);
        String assessmentYear = detail.getFinancialYear();
        String assessmentNumber = null != detail.getAssessmentNumber() ? detail.getAssessmentNumber() : criteria.getAssessmentNumber();
        String tenantId = null != property.getTenantId() ? property.getTenantId() : criteria.getTenantId();

        Map<String, Category> taxHeadCategoryMap = ((List<TaxHeadMaster>) masterMap.get(TAXHEADMASTER_MASTER_KEY)).stream()
                .collect(Collectors.toMap(TaxHeadMaster::getCode, TaxHeadMaster::getCategory));

        BigDecimal taxAmt = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;
        BigDecimal exemption = BigDecimal.ZERO;
        BigDecimal rebate = BigDecimal.ZERO;
        BigDecimal ptTax = BigDecimal.ZERO;

        for (TaxHeadEstimate estimate : estimates) {
            Category category = taxHeadCategoryMap.get(estimate.getTaxHeadCode());
            estimate.setCategory(category);

            switch (category) {
                case TAX:
                    taxAmt = taxAmt.add(estimate.getEstimateAmount());
                    if (estimate.getTaxHeadCode().equalsIgnoreCase(PT_TAX))
                        ptTax = ptTax.add(estimate.getEstimateAmount());
                    break;
                case PENALTY:
                    penalty = penalty.add(estimate.getEstimateAmount());
                    break;
                case REBATE:
                    rebate = rebate.add(estimate.getEstimateAmount());
                    break;
                case EXEMPTION:
                    exemption = exemption.add(estimate.getEstimateAmount());
                    break;
                default:
                    taxAmt = taxAmt.add(estimate.getEstimateAmount());
                    break;
            }
        }

        TaxHeadEstimate decimalEstimate = payService.roundOfDecimals(taxAmt.add(penalty), rebate.add(exemption));
        if (null != decimalEstimate) {
            decimalEstimate.setCategory(taxHeadCategoryMap.get(decimalEstimate.getTaxHeadCode()));
            estimates.add(decimalEstimate);
            if (decimalEstimate.getEstimateAmount().compareTo(BigDecimal.ZERO) >= 0)
                taxAmt = taxAmt.add(decimalEstimate.getEstimateAmount());
            else
                rebate = rebate.add(decimalEstimate.getEstimateAmount());
        }

        BigDecimal totalAmount = taxAmt.add(penalty).add(rebate).add(exemption);
        Demand oldDemand = utils.getLatestDemandForCurrentFinancialYear(requestInfo, criteria);
        BigDecimal collectedAmtForOldDemand = demandService.getCarryForwardAndCancelOldDemand(ptTax, criteria, requestInfo, oldDemand, false);

        if (collectedAmtForOldDemand.compareTo(BigDecimal.ZERO) > 0) {
            estimates.add(TaxHeadEstimate.builder()
                    .taxHeadCode(PT_ADVANCE_CARRYFORWARD)
                    .estimateAmount(collectedAmtForOldDemand).build());
        } else if (collectedAmtForOldDemand.compareTo(BigDecimal.ZERO) < 0) {
            throw new CustomException(EG_PT_DEPRECIATING_ASSESSMENT_ERROR, EG_PT_DEPRECIATING_ASSESSMENT_ERROR_MSG_ESTIMATE);
        }

        log.info("CommonEstimationUtil - Calculation prepared successfully. Total: {}", totalAmount);
        return Calculation.builder()
                .totalAmount(totalAmount.subtract(collectedAmtForOldDemand))
                .taxAmount(taxAmt)
                .penalty(penalty)
                .exemption(exemption)
                .rebate(rebate)
                .fromDate(criteria.getFromDate())
                .toDate(criteria.getToDate())
                .tenantId(tenantId)
                .serviceNumber(property.getPropertyId())
                .taxHeadEstimates(estimates)
                .billingSlabIds(billingSlabIds)
                .build();
    }

    /**
     * Logs strategy resolution details
     *
     * @param localityCode the locality code
     * @param strategyUsed the strategy that will be used
     * @param isDefault    whether default strategy is being used
     */
    public void logStrategyResolution(String localityCode, String strategyUsed, boolean isDefault) {
        String message = isDefault
                ? "Using DEFAULT estimation strategy - no locality-specific implementation found for: {}"
                : "Using locality-specific estimation strategy: {} for locality: {}";
        if (isDefault) {
            log.info(message, localityCode);
        } else {
            log.info(message, strategyUsed, localityCode);
        }
    }

    /**
     * Logs calculation start with context information
     *
     * @param localityCode the locality code
     * @param strategyName the estimation strategy being used
     * @param propertyId   the property ID
     */
    public void logCalculationStart(String localityCode, String strategyName, String propertyId) {
        log.info("Starting tax calculation - Locality: {}, Strategy: {}, PropertyId: {}",
                localityCode, strategyName, propertyId);
    }


    /**
     * Gets property detail safely
     *
     * @param property the property object
     * @return first property detail
     * @throws CustomException if property details are not available
     */
    public PropertyDetail getPropertyDetail(Property property) {
        validateProperty(property);
        PropertyDetail detail = property.getPropertyDetails().get(0);
        if (detail == null) {
            log.error("First property detail is null");
            throw new CustomException("INVALID_PROPERTY_DETAIL", "Property detail cannot be null");
        }
        return detail;
    }

    /**
     * Safely retrieves master map value
     *
     * @param masterMap the master data map
     * @param key       the key to retrieve
     * @return the value or null if not found
     */
    public Object getSafeMasterValue(Map<String, Object> masterMap, String key) {
        if (masterMap == null || StringUtils.isEmpty(key)) {
            return null;
        }
        return masterMap.get(key);
    }

    /**
     * Rounds BigDecimal to 2 decimal places
     *
     * @param value the value to round
     * @return rounded value
     */
    public BigDecimal roundToTwoDecimals(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * Validates financial year format
     * Expected format: YYYY-YY (e.g., 2023-24)
     *
     * @param financialYear the financial year to validate
     * @return true if format is valid
     */
    public boolean isValidFinancialYear(String financialYear) {
        if (StringUtils.isEmpty(financialYear)) {
            return false;
        }
        return financialYear.matches("\\d{4}-\\d{2}");
    }

    /**
     * Extracts locality code from property's address
     * Format: state.city (e.g., cg.jagdalpur, cg.bhilai)
     *
     * @param property the property object
     * @return locality code or empty string if not found
     */
    public String extractLocalityCode(Property property) {
        try {
            if (property == null || property.getAddress() == null || property.getAddress().getLocality() == null) {
                log.warn("Property address or locality is null, returning default locality");
                return "";
            }
            String localityCode = property.getAddress().getLocality().getCode();
            log.info("Extracted locality code: {}", localityCode);
            return StringUtils.isNotEmpty(localityCode) ? localityCode : "";
        } catch (Exception e) {
            log.error("Error extracting locality code from property address: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * Validates if locality code matches the specified pattern
     *
     * @param tenantId the locality code to validate
     * @return true if locality code is valid and not empty
     */
    public boolean isValidTenantCode(String tenantId) {
        return StringUtils.isNotEmpty(tenantId) && tenantId.contains(".");
    }

    /**
     * Gets bean name suffix from locality code
     * Example: cg.jagdalpur → CgJagdalpur (camelCase with capitals)
     *
     * @param tenantId the locality code
     * @return bean name suffix
     */
    public String getBeanNameSuffix(String tenantId) {
        if (StringUtils.isEmpty(tenantId)) {
            return "";
        }
        try {
            String[] parts = tenantId.split("\\.");
            if (parts.length < 2) {
                return "";
            }
            String state = capitalize(parts[0]);
            String city = capitalize(parts[1]);
            String suffix = state + city;
            log.info("Generated bean name suffix from tenant code {}: {}", tenantId, suffix);
            return suffix;
        } catch (Exception e) {
            log.error("Error generating bean name suffix for tenant code: {}", tenantId, e);
            return "";
        }
    }

    /**
     * Capitalizes the first letter of a string
     *
     * @param str the string to capitalize
     * @return capitalized string
     */
    private String capitalize(String str) {
        if (StringUtils.isEmpty(str)) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    /**
     * Validates property and its details for calculation
     *
     * @param property the property to validate
     * @throws CustomException if property is invalid
     */
    public void validateProperty(Property property) {
        if (property == null) {
            log.error("Property object is null");
            throw new CustomException("INVALID_PROPERTY", "Property cannot be null");
        }
        if (property.getPropertyDetails() == null || property.getPropertyDetails().isEmpty()) {
            log.error("Property details list is empty or null");
            throw new CustomException("INVALID_PROPERTY_DETAILS", "Property must have at least one detail");
        }
    }

    /**
     * Logs calculation completion with result
     *
     * @param strategyName the estimation strategy used
     * @param totalAmount  the calculated total amount
     * @param propertyId   the property ID
     */
    public void logCalculationComplete(String strategyName, BigDecimal totalAmount, String propertyId) {
        log.info("Tax calculation completed - Strategy: {}, TotalAmount: {}, PropertyId: {}",
                strategyName, totalAmount, propertyId);
    }


}

