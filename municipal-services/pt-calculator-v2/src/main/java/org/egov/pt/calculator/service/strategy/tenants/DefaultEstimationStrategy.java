package org.egov.pt.calculator.service.strategy.tenants;

import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pt.calculator.service.*;
import org.egov.pt.calculator.service.strategy.TenantBasedEstimationStrategy;
import org.egov.pt.calculator.util.*;
import org.egov.pt.calculator.validator.CalculationValidator;
import org.egov.pt.calculator.web.models.*;
import org.egov.pt.calculator.web.models.BillingSlabSearchCriteria;
import org.egov.pt.calculator.web.models.collections.Payment;
import org.egov.pt.calculator.web.models.demand.Category;
import org.egov.pt.calculator.web.models.demand.Demand;
import org.egov.pt.calculator.web.models.demand.TaxHeadMaster;
import org.egov.pt.calculator.web.models.demand.TaxPeriod;
import org.egov.pt.calculator.web.models.property.*;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.egov.pt.calculator.util.CalculatorConstants.*;

/**
 * DefaultEstimationStrategy - Default implementation of TenantBasedEstimationStrategy
 * This strategy contains the existing calculation logic and is used as fallback
 * when no tenant-specific implementation is found
 *
 * @author PT Calculator Service
 * @version 1.0
 */
@Service
@Slf4j
public class DefaultEstimationStrategy implements TenantBasedEstimationStrategy {

    @Autowired
    private BillingSlabService billingSlabService;

    @Autowired
    private PayService payService;

    @Autowired
    private Configurations configs;

    @Autowired
    private MasterDataService mDataService;

    @Autowired
    private DemandService demandService;

    @Autowired
    private PBFirecessUtils firecessUtils;

    @Autowired
    CalculationValidator calcValidator;

    @Autowired
    private EnrichmentService enrichmentService;

    @Autowired
    private CalculatorUtils utils;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private EstimationCommonUtil estimationCommonUtil;

    @Value("${customization.pbfirecesslogic:false}")
    Boolean usePBFirecessLogic;

    @Override
    public Calculation calculateTax(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) throws Exception {
        log.info("DefaultEstimationStrategy - Starting tax calculation");
        try {
            return getCalculation(requestInfo, criteria, masterMap);
        } catch (Exception e) {
            log.error("Error in DefaultEstimationStrategy.calculateTax: {}", e.getMessage(), e);
            throw new CustomException("DEFAULT_ESTIMATION_ERROR", "Default estimation calculation failed: " + e.getMessage());
        }
    }

    @Override
    public String getStrategyName() {
        return "DEFAULT_ESTIMATION_STRATEGY";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    /**
     * Prepares Calculation Response based on the provided TaxHeadEstimate List
     * All the credit taxHeads will be payable and all debit tax heads will be deducted.
     */
    private Calculation getCalculation(RequestInfo requestInfo, CalculationCriteria criteria, Map<String, Object> masterMap) {
        log.info("DefaultEstimationStrategy - Preparing calculation");

        Map<String, List> estimatesAndBillingSlabs = getEstimationMap(criteria, requestInfo, masterMap);

        List<TaxHeadEstimate> estimates = estimatesAndBillingSlabs.get("estimates");
        List<String> billingSlabIds = estimatesAndBillingSlabs.get("billingSlabIds");

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

        if (collectedAmtForOldDemand.compareTo(BigDecimal.ZERO) > 0)
            estimates.add(TaxHeadEstimate.builder()
                    .taxHeadCode(PT_ADVANCE_CARRYFORWARD)
                    .estimateAmount(collectedAmtForOldDemand).build());
        else if (collectedAmtForOldDemand.compareTo(BigDecimal.ZERO) < 0)
            throw new CustomException(EG_PT_DEPRECIATING_ASSESSMENT_ERROR, EG_PT_DEPRECIATING_ASSESSMENT_ERROR_MSG_ESTIMATE);

        log.info("DefaultEstimationStrategy - Calculation prepared successfully. Total: {}", totalAmount);
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
     * Generates a Map with estimates and billing slab IDs
     */
    private Map<String, List> getEstimationMap(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) {
        log.debug("DefaultEstimationStrategy - Calculating estimation map");

        BigDecimal taxAmt = BigDecimal.ZERO;
        BigDecimal usageExemption = BigDecimal.ZERO;
        Property property = criteria.getProperty();
        PropertyDetail detail = property.getPropertyDetails().get(0);
        String assessmentYear = detail.getFinancialYear();
        String tenantId = property.getTenantId();

        if (criteria.getFromDate() == null || criteria.getToDate() == null)
            enrichmentService.enrichDemandPeriod(criteria, assessmentYear, masterMap);

        List<BillingSlab> filteredBillingSlabs = getSlabsFiltered(property, criteria.getFinancialYear(), requestInfo);

        Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap = new HashMap<>();
        Map<String, JSONArray> timeBasedExemptionMasterMap = new HashMap<>();
        mDataService.setPropertyMasterValues(requestInfo, tenantId, propertyBasedExemptionMasterMap,
                timeBasedExemptionMasterMap);

        List<String> billingSlabIds = new LinkedList<>();

        if (PT_TYPE_VACANT_LAND.equalsIgnoreCase(detail.getPropertyType()) && filteredBillingSlabs.size() != 1)
            throw new CustomException(PT_ESTIMATE_BILLINGSLABS_UNMATCH_VACANCT, PT_ESTIMATE_BILLINGSLABS_UNMATCH_VACANT_MSG
                    .replace("{count}", String.valueOf(filteredBillingSlabs.size())));

        else if (PT_TYPE_VACANT_LAND.equalsIgnoreCase(detail.getPropertyType())) {
            taxAmt = taxAmt.add(BigDecimal.valueOf(filteredBillingSlabs.get(0).getUnitRate() * detail.getLandArea()));
        } else {
            double unBuiltRate = 0.0;
            int groundUnitsCount = 0;
            Double groundUnitsArea = 0.0;
            int i = 0;

            for (Unit unit : detail.getUnits()) {
                BillingSlab slab = getSlabForCalc(filteredBillingSlabs, unit);
                BigDecimal currentUnitTax = getTaxForUnit(slab, unit);
                billingSlabIds.add(slab.getId() + "|" + i);

                if (unit.getFloorNo().equalsIgnoreCase("0")) {
                    groundUnitsCount += 1;
                    groundUnitsArea += unit.getUnitArea();
                    if (null != slab.getUnBuiltUnitRate())
                        unBuiltRate += slab.getUnBuiltUnitRate();
                }
                taxAmt = taxAmt.add(currentUnitTax);
                usageExemption = usageExemption
                        .add(getExemption(unit, currentUnitTax, assessmentYear, propertyBasedExemptionMasterMap));
                i++;
            }

            taxAmt = taxAmt.add(getUnBuiltRate(detail, unBuiltRate, groundUnitsCount, groundUnitsArea));

            if (detail.getUnits().size() == 1)
                usageExemption = getExemption(detail.getUnits().get(0), taxAmt, assessmentYear,
                        propertyBasedExemptionMasterMap);
        }

        List<TaxHeadEstimate> taxHeadEstimates = getEstimatesForTax(requestInfo, taxAmt, usageExemption, property, propertyBasedExemptionMasterMap,
                timeBasedExemptionMasterMap, masterMap);

        Map<String, List> estimatesAndBillingSlabs = new HashMap<>();
        estimatesAndBillingSlabs.put("estimates", taxHeadEstimates);
        estimatesAndBillingSlabs.put("billingSlabIds", billingSlabIds);

        return estimatesAndBillingSlabs;
    }

    // ... helper methods (same as in original EstimationService)
    private BigDecimal getUnBuiltRate(PropertyDetail detail, double unBuiltRate, int groundUnitsCount, Double groundUnitsArea) {
        BigDecimal unBuiltAmt = BigDecimal.ZERO;
        if (0.0 < unBuiltRate && null != detail.getLandArea() && groundUnitsCount > 0) {
            double diffArea = null != detail.getBuildUpArea() ? detail.getLandArea() - detail.getBuildUpArea()
                    : detail.getLandArea() - groundUnitsArea;
            diffArea = diffArea < 0.0 ? 0.0 : diffArea;
            unBuiltAmt = unBuiltAmt.add(BigDecimal.valueOf((unBuiltRate / groundUnitsCount) * (diffArea)));
        }
        return unBuiltAmt;
    }

    private BigDecimal getTaxForUnit(BillingSlab slab, Unit unit) {
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
            if (unit.getArv() == null)
                throw new CustomException(EG_PT_ESTIMATE_ARV_NULL, EG_PT_ESTIMATE_ARV_NULL_MSG);

            BigDecimal multiplier;
            if (null != slab.getArvPercent())
                multiplier = BigDecimal.valueOf(slab.getArvPercent() / 100);
            else
                multiplier = BigDecimal.valueOf(configs.getArvPercent() / 100);
            currentUnitTax = unit.getArv().multiply(multiplier);
        } else {
            currentUnitTax = BigDecimal.valueOf(unit.getUnitArea() * slab.getUnitRate());
        }
        return currentUnitTax;
    }

    private List<TaxHeadEstimate> getEstimatesForTax(RequestInfo requestInfo, BigDecimal taxAmt, BigDecimal usageExemption, Property property,
                                                     Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap,
                                                     Map<String, JSONArray> timeBasedExemeptionMasterMap, Map<String, Object> masterMap) {

        PropertyDetail detail = property.getPropertyDetails().get(0);
        BigDecimal payableTax = taxAmt;
        List<TaxHeadEstimate> estimates = new ArrayList<>();

        String assessmentYear = detail.getFinancialYear();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TAX).estimateAmount(taxAmt.setScale(2, 2)).build());

        usageExemption = usageExemption.setScale(2, 2).negate();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_UNIT_USAGE_EXEMPTION).estimateAmount(
                usageExemption).build());
        payableTax = payableTax.add(usageExemption);

        BigDecimal userExemption = getExemption(detail.getOwners(), payableTax, assessmentYear,
                propertyBasedExemptionMasterMap).setScale(2, 2).negate();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_OWNER_EXEMPTION).estimateAmount(userExemption).build());
        payableTax = payableTax.add(userExemption);

        List<Object> fireCessMasterList = timeBasedExemeptionMasterMap.get(CalculatorConstants.FIRE_CESS_MASTER);
        BigDecimal fireCess;

        if (usePBFirecessLogic) {
            fireCess = firecessUtils.getPBFireCess(payableTax, assessmentYear, fireCessMasterList, detail);
            estimates.add(
                    TaxHeadEstimate.builder().taxHeadCode(PT_FIRE_CESS).estimateAmount(fireCess.setScale(2, 2)).build());
        } else {
            fireCess = mDataService.getCess(payableTax, assessmentYear, fireCessMasterList);
            estimates.add(
                    TaxHeadEstimate.builder().taxHeadCode(PT_FIRE_CESS).estimateAmount(fireCess.setScale(2, 2)).build());
        }

        List<Object> cancerCessMasterList = timeBasedExemeptionMasterMap.get(CalculatorConstants.CANCER_CESS_MASTER);
        BigDecimal cancerCess = mDataService.getCess(payableTax, assessmentYear, cancerCessMasterList);
        estimates.add(
                TaxHeadEstimate.builder().taxHeadCode(PT_CANCER_CESS).estimateAmount(cancerCess.setScale(2, 2)).build());

        Map<String, Map<String, Object>> financialYearMaster = (Map<String, Map<String, Object>>) masterMap.get(FINANCIALYEAR_MASTER_KEY);
        Map<String, Object> finYearMap = financialYearMaster.get(assessmentYear);
        Long fromDate = (Long) finYearMap.get(FINANCIAL_YEAR_STARTING_DATE);
        Long toDate = (Long) finYearMap.get(FINANCIAL_YEAR_ENDING_DATE);

        TaxPeriod taxPeriod = TaxPeriod.builder().fromDate(fromDate).toDate(toDate).build();

        List<Payment> payments = new LinkedList<>();

        if (null != property.getPropertyId() && null != property.getTenantId()) {
            payments = paymentService.getPaymentsFromProperty(property, RequestInfoWrapper.builder().requestInfo(requestInfo).build());
        }

        Map<String, BigDecimal> rebatePenaltyMap = payService.applyPenaltyRebateAndInterest(payableTax, BigDecimal.ZERO,
                assessmentYear, timeBasedExemeptionMasterMap, payments, taxPeriod);

        if (null != rebatePenaltyMap) {
            BigDecimal rebate = rebatePenaltyMap.get(PT_TIME_REBATE);
            BigDecimal penalty = rebatePenaltyMap.get(PT_TIME_PENALTY);
            BigDecimal interest = rebatePenaltyMap.get(PT_TIME_INTEREST);
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TIME_REBATE).estimateAmount(rebate).build());
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TIME_PENALTY).estimateAmount(penalty).build());
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TIME_INTEREST).estimateAmount(interest).build());
            payableTax = payableTax.add(rebate).add(penalty).add(interest);
        }

        if (null != detail.getAdhocPenalty())
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_ADHOC_PENALTY)
                    .estimateAmount(detail.getAdhocPenalty()).build());

        if (null != detail.getAdhocExemption() && detail.getAdhocExemption().compareTo(payableTax.add(fireCess)) <= 0) {
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_ADHOC_REBATE)
                    .estimateAmount(detail.getAdhocExemption().negate()).build());
        } else if (null != detail.getAdhocExemption()) {
            throw new CustomException(PT_ADHOC_REBATE_INVALID_AMOUNT, PT_ADHOC_REBATE_INVALID_AMOUNT_MSG + taxAmt);
        }
        return estimates;
    }

    private List<BillingSlab> getSlabsFiltered(Property property, String financialYear, RequestInfo requestInfo) {
        PropertyDetail detail = property.getPropertyDetails().get(0);
        log.debug("Financial Year in Criteria: {}", financialYear);

        String tenantId = property.getTenantId();
        String validFrom = financialYear.split("-")[0] + "-04-01";
        String validTo = "20" + financialYear.split("-")[1] + "-03-31";
        BillingSlabSearchCriteria slabSearchCriteria = BillingSlabSearchCriteria.builder().tenantId(tenantId).validFrom(validFrom).validTo(validTo).build();
        List<BillingSlab> billingSlabs = billingSlabService.searchBillingSlabs(requestInfo, slabSearchCriteria)
                .getBillingSlab();

        log.debug("Slabs count: {}", billingSlabs.size());
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

    private BillingSlab getSlabForCalc(List<BillingSlab> billingSlabs, Unit unit) {
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
        if (matchingList.size() == 1)
            return matchingList.get(0);
        else if (matchingList.size() == 0)
            return null;
        else throw new CustomException(PT_ESTIMATE_BILLINGSLABS_UNMATCH, PT_ESTIMATE_BILLINGSLABS_UNMATCH_MSG
                    .replace(PT_ESTIMATE_BILLINGSLABS_UNMATCH_replace_id, matchingList.toString()) + unit);
    }

    private BigDecimal getExemption(Unit unit, BigDecimal currentUnitTax, String financialYear,
                                    Map<String, Map<String, List<Object>>> propertyMasterMap) {
        Map<String, Object> exemption = getExemptionFromUsage(unit, financialYear, propertyMasterMap);
        return mDataService.calculateApplicables(currentUnitTax, exemption);
    }

    private BigDecimal getExemption(Set<OwnerInfo> owners, BigDecimal taxAmt, String financialYear,
                                    Map<String, Map<String, List<Object>>> propertyMasterMap) {
        Map<String, List<Object>> ownerTypeMap = propertyMasterMap.get(OWNER_TYPE_MASTER);
        BigDecimal userExemption = BigDecimal.ZERO;
        final int userCount = owners.size();
        BigDecimal share = taxAmt.divide(BigDecimal.valueOf(userCount), 2, 2);

        for (OwnerInfo owner : owners) {
            if (null == ownerTypeMap.get(owner.getOwnerType()))
                continue;

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getExemptionFromUsage(Unit unit, String financialYear,
                                                      Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap) {

        Map<String, List<Object>> usageDetails = propertyBasedExemptionMasterMap.get(USAGE_DETAIL_MASTER);
        Map<String, List<Object>> usageSubMinors = propertyBasedExemptionMasterMap.get(USAGE_SUB_MINOR_MASTER);
        Map<String, List<Object>> usageMinors = propertyBasedExemptionMasterMap.get(USAGE_MINOR_MASTER);
        Map<String, List<Object>> usageMajors = propertyBasedExemptionMasterMap.get(USAGE_MAJOR_MASTER);

        Map<String, Object> applicableUsageMasterExemption = null;

        if (null != usageDetails.get(unit.getUsageCategoryDetail()))
            applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
                    usageDetails.get(unit.getUsageCategoryDetail()));

        if (isExemptionNull(applicableUsageMasterExemption)
                && null != usageSubMinors.get(unit.getUsageCategorySubMinor()))
            applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
                    usageSubMinors.get(unit.getUsageCategorySubMinor()));

        if (isExemptionNull(applicableUsageMasterExemption) && null != usageMinors.get(unit.getUsageCategoryMinor()))
            applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
                    usageMinors.get(unit.getUsageCategoryMinor()));

        if (isExemptionNull(applicableUsageMasterExemption) && null != usageMajors.get(unit.getUsageCategoryMajor()))
            applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
                    usageMajors.get(unit.getUsageCategoryMajor()));

        if (null != applicableUsageMasterExemption)
            applicableUsageMasterExemption = (Map<String, Object>) applicableUsageMasterExemption.get(EXEMPTION_FIELD_NAME);

        return applicableUsageMasterExemption;
    }

    private boolean isExemptionNull(Map<String, Object> applicableUsageMasterExemption) {
        return !(null != applicableUsageMasterExemption
                && null != applicableUsageMasterExemption.get(EXEMPTION_FIELD_NAME));
    }
}

