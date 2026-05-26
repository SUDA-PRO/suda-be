package org.egov.pt.calculator.service.strategy.tenants;

import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pt.calculator.service.EnrichmentService;
import org.egov.pt.calculator.service.MasterDataService;
import org.egov.pt.calculator.service.PayService;
import org.egov.pt.calculator.service.PaymentService;
import org.egov.pt.calculator.service.strategy.TenantBasedEstimationStrategy;
import org.egov.pt.calculator.util.CalculatorConstants;
import org.egov.pt.calculator.util.Configurations;
import org.egov.pt.calculator.util.EstimationCommonUtil;
import org.egov.pt.calculator.util.PBFirecessUtils;
import org.egov.pt.calculator.web.models.BillingSlab;
import org.egov.pt.calculator.web.models.Calculation;
import org.egov.pt.calculator.web.models.CalculationCriteria;
import org.egov.pt.calculator.web.models.TaxHeadEstimate;
import org.egov.pt.calculator.web.models.collections.Payment;
import org.egov.pt.calculator.web.models.demand.TaxPeriod;
import org.egov.pt.calculator.web.models.property.Property;
import org.egov.pt.calculator.web.models.property.PropertyDetail;
import org.egov.pt.calculator.web.models.property.RequestInfoWrapper;
import org.egov.pt.calculator.web.models.property.Unit;
import org.egov.tracer.model.CustomException;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

import static org.egov.pt.calculator.util.CalculatorConstants.*;

/**
 * CgJagdalpurEstimationStrategy - Example tenant-specific estimation strategy
 * <p>
 * This is an example implementation showing how to create a tenant-specific strategy.
 * This strategy would be used for Jagdalpur (Chhattisgarh) properties.
 * <p>
 * Bean name: cgJagdalpurEstimationStrategy (auto-discovered and injected)
 * Tenant codes: cg.jagdalpur
 * <p>
 * To create similar strategy for other tenant:
 * 1. Create new class extending this pattern
 * 2. Update bean name accordingly (e.g., CgBhilaiEstimationStrategy)
 * 3. Implement custom calculation logic as needed
 * 4. Spring will automatically discover and register the bean
 *
 * @author PT Calculator Service
 * @version 1.0
 */
@Service("cgJagdalpurEstimationStrategy")
@Slf4j
public class CgJagdalpurEstimationStrategy implements TenantBasedEstimationStrategy {

    private static final String STRATEGY_NAME = "CG_JAGDALPUR_ESTIMATION_STRATEGY";

    @Autowired
    private Configurations configs;

    @Autowired
    private EstimationCommonUtil estimationCommonUtil;

    @Autowired
    private EnrichmentService enrichmentService;

    @Autowired
    private MasterDataService mDataService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PBFirecessUtils firecessUtils;

    @Autowired
    private PayService payService;

    @Value("${customization.pbfirecesslogic:false}")
    Boolean usePBFirecessLogic;

    @Override
    public Calculation calculateTax(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) throws Exception {
        log.info("CgJagdalpurEstimationStrategy - Calculating tax for Jagdalpur");
        try {
            Calculation calculation = getCalculation(requestInfo, criteria, masterMap);
            log.info("CgJagdalpurEstimationStrategy - Tax calculation completed");
            return calculation;
        } catch (Exception e) {
            log.error("Error in CgJagdalpurEstimationStrategy: {}", e.getMessage(), e);
            throw e;
        }
    }

    private Calculation getCalculation(RequestInfo requestInfo, CalculationCriteria criteria, Map<String, Object> masterMap) {
        log.info("CgJagdalpurEstimationStrategy - Preparing calculation");

        Map<String, List> estimatesAndBillingSlabs = getEstimationMap(criteria, requestInfo, masterMap);

        List<TaxHeadEstimate> estimates = estimatesAndBillingSlabs.get("estimates");
        List<String> billingSlabIds = estimatesAndBillingSlabs.get("billingSlabIds");

        return estimationCommonUtil.getCalculation(requestInfo, criteria, masterMap, estimates, billingSlabIds);
    }


    /**
     * Generates a Map with estimates and billing slab IDs
     */
    private Map<String, List> getEstimationMap(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) {
        log.debug("CgJagdalpurEstimationStrategy - Calculating estimation map");

        BigDecimal taxAmt = BigDecimal.ZERO;
        BigDecimal usageExemption = BigDecimal.ZERO;
        Property property = criteria.getProperty();
        PropertyDetail detail = property.getPropertyDetails().get(0);
        String assessmentYear = detail.getFinancialYear();
        String tenantId = property.getTenantId();

        if (criteria.getFromDate() == null || criteria.getToDate() == null) {
            enrichmentService.enrichDemandPeriod(criteria, assessmentYear, masterMap);
        }

        List<BillingSlab> filteredBillingSlabs = estimationCommonUtil.getCommonSlabsFirstLevelFiltered(property, criteria.getFinancialYear(), requestInfo);

        Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap = new HashMap<>();
        Map<String, JSONArray> timeBasedExemptionMasterMap = new HashMap<>();
        mDataService.setPropertyMasterValues(requestInfo, tenantId, propertyBasedExemptionMasterMap,
                timeBasedExemptionMasterMap);

        List<String> billingSlabIds = new LinkedList<>();

        if (PT_TYPE_VACANT_LAND.equalsIgnoreCase(detail.getPropertyType()) && filteredBillingSlabs.size() != 1) {
            throw new CustomException(PT_ESTIMATE_BILLINGSLABS_UNMATCH_VACANCT, PT_ESTIMATE_BILLINGSLABS_UNMATCH_VACANT_MSG
                    .replace("{count}", String.valueOf(filteredBillingSlabs.size())));
        } else if (PT_TYPE_VACANT_LAND.equalsIgnoreCase(detail.getPropertyType())) {
            taxAmt = taxAmt.add(BigDecimal.valueOf(filteredBillingSlabs.get(0).getUnitRate() * detail.getLandArea()));
        } else {
            double unBuiltRate = 0.0;
            int groundUnitsCount = 0;
            Double groundUnitsArea = 0.0;
            int i = 0;

            for (Unit unit : detail.getUnits()) {
                BillingSlab slab = estimationCommonUtil.getCommonUniqueSlabSecondLevelFiltered(filteredBillingSlabs, unit);
                BigDecimal currentUnitTax = getTenantTaxForUnit(slab, unit, property);
                billingSlabIds.add(slab.getId() + "|" + i);

                if (unit.getFloorNo().equalsIgnoreCase("0")) {
                    groundUnitsCount += 1;
                    groundUnitsArea += unit.getUnitArea();
                    if (null != slab.getUnBuiltUnitRate())
                        unBuiltRate += slab.getUnBuiltUnitRate();
                }
                taxAmt = taxAmt.add(currentUnitTax);
                usageExemption = calculateUnitUsageExemption(unit, usageExemption, currentUnitTax, assessmentYear, propertyBasedExemptionMasterMap);
                i++;
            }

            taxAmt = taxAmt.add(estimationCommonUtil.getCommonUnBuiltRate(detail, unBuiltRate, groundUnitsCount, groundUnitsArea));

            usageExemption = calculateSingleUnitUsageExemption(detail, usageExemption, taxAmt, assessmentYear, propertyBasedExemptionMasterMap);
        }

        List<TaxHeadEstimate> taxHeadEstimates = getEstimatesForTax(requestInfo, taxAmt, usageExemption, property, propertyBasedExemptionMasterMap,
                timeBasedExemptionMasterMap, masterMap);

        Map<String, List> estimatesAndBillingSlabs = new HashMap<>();
        estimatesAndBillingSlabs.put("estimates", taxHeadEstimates);
        estimatesAndBillingSlabs.put("billingSlabIds", billingSlabIds);

        return estimatesAndBillingSlabs;
    }

    private BigDecimal calculateSingleUnitUsageExemption(PropertyDetail detail, BigDecimal usageExemption, BigDecimal taxAmt, String assessmentYear, Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap) {
        if (detail.getUnits().size() == 1) {
            usageExemption = estimationCommonUtil.getCommonExemption(detail.getUnits().get(0), taxAmt, assessmentYear,
                    propertyBasedExemptionMasterMap);
        }
        return usageExemption;
    }

    @NotNull
    private BigDecimal calculateUnitUsageExemption(Unit unit, BigDecimal usageExemption, BigDecimal currentUnitTax, String assessmentYear, Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap) {
        usageExemption = usageExemption
                .add(estimationCommonUtil.getCommonExemption(unit, currentUnitTax, assessmentYear, propertyBasedExemptionMasterMap));
        return usageExemption;
    }

    private List<TaxHeadEstimate> getEstimatesForTax(RequestInfo requestInfo, BigDecimal taxAmt, BigDecimal usageExemption, Property property,
                                                     Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap,
                                                     Map<String, JSONArray> timeBasedExemeptionMasterMap, Map<String, Object> masterMap) {

        PropertyDetail detail = property.getPropertyDetails().get(0);
        BigDecimal payableTax = taxAmt;
        List<TaxHeadEstimate> estimates = new ArrayList<>();

        String assessmentYear = detail.getFinancialYear();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TAX).estimateAmount(taxAmt.setScale(2, 2)).build());

        payableTax = applyUsageExemption(usageExemption, estimates, payableTax);

        payableTax = applyOwnerExemption(propertyBasedExemptionMasterMap, detail, payableTax, assessmentYear, estimates);

        List<Object> fireCessMasterList = timeBasedExemeptionMasterMap.get(CalculatorConstants.FIRE_CESS_MASTER);

        BigDecimal fireCess;
        fireCess = applyFireCess(payableTax, assessmentYear, fireCessMasterList, detail, estimates);

        applyCancerCess(timeBasedExemeptionMasterMap, payableTax, assessmentYear, estimates);

        Map<String, Map<String, Object>> financialYearMaster = (Map<String, Map<String, Object>>) masterMap.get(FINANCIALYEAR_MASTER_KEY);
        Map<String, Object> finYearMap = financialYearMaster.get(assessmentYear);
        Long fromDate = (Long) finYearMap.get(FINANCIAL_YEAR_STARTING_DATE);
        Long toDate = (Long) finYearMap.get(FINANCIAL_YEAR_ENDING_DATE);

        TaxPeriod taxPeriod = TaxPeriod.builder().fromDate(fromDate).toDate(toDate).build();

        List<Payment> payments = new LinkedList<>();
        if (null != property.getPropertyId() && null != property.getTenantId()) {
            payments = paymentService.getPaymentsFromProperty(property, RequestInfoWrapper.builder().requestInfo(requestInfo).build());
        }

        payableTax = applyRebatePenaltyInterest(timeBasedExemeptionMasterMap, payableTax, assessmentYear, payments, taxPeriod, estimates);

        applyAdhocPenalty(detail, estimates);

        applyAdhocRebate(taxAmt, detail, payableTax, fireCess, estimates);

        return estimates;
    }

    private BigDecimal applyOwnerExemption(Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap, PropertyDetail detail, BigDecimal payableTax, String assessmentYear, List<TaxHeadEstimate> estimates) {
        BigDecimal userExemption = estimationCommonUtil.getCommonOwnerExemption(detail.getOwners(), payableTax, assessmentYear,
                propertyBasedExemptionMasterMap).setScale(2, 2).negate();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_OWNER_EXEMPTION).estimateAmount(userExemption).build());
        payableTax = payableTax.add(userExemption);
        return payableTax;
    }

    private static BigDecimal applyUsageExemption(BigDecimal usageExemption, List<TaxHeadEstimate> estimates, BigDecimal payableTax) {
        usageExemption = usageExemption.setScale(2, 2).negate();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_UNIT_USAGE_EXEMPTION).estimateAmount(
                usageExemption).build());
        payableTax = payableTax.add(usageExemption);
        return payableTax;
    }

    private BigDecimal applyFireCess(BigDecimal payableTax, String assessmentYear, List<Object> fireCessMasterList, PropertyDetail detail, List<TaxHeadEstimate> estimates) {
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
        return fireCess;
    }

    private void applyCancerCess(Map<String, JSONArray> timeBasedExemeptionMasterMap, BigDecimal payableTax, String assessmentYear, List<TaxHeadEstimate> estimates) {
        List<Object> cancerCessMasterList = timeBasedExemeptionMasterMap.get(CalculatorConstants.CANCER_CESS_MASTER);
        BigDecimal cancerCess = mDataService.getCess(payableTax, assessmentYear, cancerCessMasterList);
        estimates.add(
                TaxHeadEstimate.builder().taxHeadCode(PT_CANCER_CESS).estimateAmount(cancerCess.setScale(2, 2)).build());
    }

    private static void applyAdhocRebate(BigDecimal taxAmt, PropertyDetail detail, BigDecimal payableTax, BigDecimal fireCess, List<TaxHeadEstimate> estimates) {
        if (null != detail.getAdhocExemption() && detail.getAdhocExemption().compareTo(payableTax.add(fireCess)) <= 0) {
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_ADHOC_REBATE)
                    .estimateAmount(detail.getAdhocExemption().negate()).build());
        } else if (null != detail.getAdhocExemption()) {
            throw new CustomException(PT_ADHOC_REBATE_INVALID_AMOUNT, PT_ADHOC_REBATE_INVALID_AMOUNT_MSG + taxAmt);
        }
    }

    private static void applyAdhocPenalty(PropertyDetail detail, List<TaxHeadEstimate> estimates) {
        if (null != detail.getAdhocPenalty()) {
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_ADHOC_PENALTY)
                    .estimateAmount(detail.getAdhocPenalty()).build());
        }
    }

    private BigDecimal applyRebatePenaltyInterest(Map<String, JSONArray> timeBasedExemeptionMasterMap, BigDecimal payableTax, String assessmentYear, List<Payment> payments, TaxPeriod taxPeriod, List<TaxHeadEstimate> estimates) {
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
        return payableTax;
    }

    private BigDecimal getTenantTaxForUnit(BillingSlab slab, Unit unit, Property property) {
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

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public boolean isDefault() {
        return false;
    }
}

