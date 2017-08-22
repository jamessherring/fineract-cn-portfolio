/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.individuallending.internal.service;

import io.mifos.individuallending.api.v1.domain.caseinstance.ChargeName;
import io.mifos.individuallending.api.v1.domain.caseinstance.PlannedPayment;
import io.mifos.individuallending.api.v1.domain.caseinstance.PlannedPaymentPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * @author Myrle Krantz
 */
@Service
public class IndividualLoanService {
  private final ScheduledChargesService scheduledChargesService;

  @Autowired
  public IndividualLoanService(final ScheduledChargesService scheduledChargesService) {
    this.scheduledChargesService = scheduledChargesService;
  }

  public PlannedPaymentPage getPlannedPaymentsPage(
      final DataContextOfAction dataContextOfAction,
      final int pageIndex,
      final int size,
      final @Nonnull LocalDate initialDisbursalDate) {
    final int minorCurrencyUnitDigits = dataContextOfAction.getProductEntity().getMinorCurrencyUnitDigits();

    final List<ScheduledAction> scheduledActions = ScheduledActionHelpers.getHypotheticalScheduledActions(initialDisbursalDate, dataContextOfAction.getCaseParameters());

    final List<ScheduledCharge> scheduledCharges = scheduledChargesService.getScheduledCharges(dataContextOfAction.getProductEntity().getIdentifier(), scheduledActions);

    final BigDecimal loanPaymentSize = CostComponentService.getLoanPaymentSize(
        dataContextOfAction.getCaseParametersEntity().getBalanceRangeMaximum(),
        dataContextOfAction.getInterest(),
        minorCurrencyUnitDigits,
        scheduledCharges);

    final List<PlannedPayment> plannedPaymentsElements = getPlannedPaymentsElements(
        dataContextOfAction.getCaseParametersEntity().getBalanceRangeMaximum(),
        minorCurrencyUnitDigits,
        scheduledCharges,
        loanPaymentSize,
        dataContextOfAction.getInterest());

    final Set<ChargeName> chargeNames = scheduledCharges.stream()
            .map(IndividualLoanService::chargeNameFromChargeDefinition)
            .collect(Collectors.toSet());

    return constructPage(pageIndex, size, plannedPaymentsElements, chargeNames);
  }

  private static PlannedPaymentPage constructPage(
          final int pageIndex,
          final int size,
          final List<PlannedPayment> plannedPaymentsElements,
          final Set<ChargeName> chargeNames) {
    final int fromIndex = size*pageIndex;
    final int toIndex = Math.min(size*(pageIndex+1), plannedPaymentsElements.size());
    final List<PlannedPayment> elements = plannedPaymentsElements.subList(fromIndex, toIndex);

    final PlannedPaymentPage ret = new PlannedPaymentPage();
    ret.setElements(elements);
    ret.setChargeNames(chargeNames);
    ret.setTotalElements((long) plannedPaymentsElements.size());
    final int partialPage = Math.floorMod(plannedPaymentsElements.size(), size) == 0 ? 0 : 1;
    ret.setTotalPages(Math.floorDiv(plannedPaymentsElements.size(), size)+ partialPage);

    return ret;
  }

  private static ChargeName chargeNameFromChargeDefinition(final ScheduledCharge scheduledCharge) {
    return new ChargeName(scheduledCharge.getChargeDefinition().getIdentifier(), scheduledCharge.getChargeDefinition().getName());
  }

  static private List<PlannedPayment> getPlannedPaymentsElements(
      final BigDecimal initialBalance,
      final int minorCurrencyUnitDigits,
      final List<ScheduledCharge> scheduledCharges,
      final BigDecimal loanPaymentSize,
      final BigDecimal interest) {
    final Map<Period, SortedSet<ScheduledCharge>> orderedScheduledChargesGroupedByPeriod
        = scheduledCharges.stream()
        .collect(Collectors.groupingBy(IndividualLoanService::getPeriodFromScheduledCharge,
            Collectors.mapping(x -> x,
                Collector.of(
                    () -> new TreeSet<>(new ScheduledChargesService.ScheduledChargeComparator()),
                    SortedSet::add,
                    (left, right) -> { left.addAll(right); return left; }))));

    final List<Period> sortedRepaymentPeriods
        = orderedScheduledChargesGroupedByPeriod.keySet().stream()
        .sorted()
        .collect(Collector.of(ArrayList::new, List::add, (left, right) -> { left.addAll(right); return left; }));

    BigDecimal balance = initialBalance.setScale(minorCurrencyUnitDigits, BigDecimal.ROUND_HALF_EVEN);
    final List<PlannedPayment> plannedPayments = new ArrayList<>();
    for (int i = 0; i < sortedRepaymentPeriods.size(); i++)
    {
      final Period repaymentPeriod = sortedRepaymentPeriods.get(i);
      final BigDecimal currentLoanPaymentSize;
      if (repaymentPeriod.isDefined()) {
        // last repayment period: Force the proposed payment to "overhang". Cost component calculation
        // corrects last loan payment downwards but not upwards.
        if (i == sortedRepaymentPeriods.size() - 1)
          currentLoanPaymentSize = loanPaymentSize.add(BigDecimal.valueOf(sortedRepaymentPeriods.size()));
        else
          currentLoanPaymentSize = loanPaymentSize;
      }
      else
        currentLoanPaymentSize = BigDecimal.ZERO;

      final SortedSet<ScheduledCharge> scheduledChargesInPeriod = orderedScheduledChargesGroupedByPeriod.get(repaymentPeriod);
      final CostComponentsForRepaymentPeriod costComponentsForRepaymentPeriod =
              CostComponentService.getCostComponentsForScheduledCharges(
                  Collections.emptyMap(),
                  scheduledChargesInPeriod,
                  initialBalance,
                  balance,
                  currentLoanPaymentSize,
                  interest,
                  minorCurrencyUnitDigits,
                  false);

      final PlannedPayment plannedPayment = new PlannedPayment();
      plannedPayment.setCostComponents(new ArrayList<>(costComponentsForRepaymentPeriod.getCostComponents().values()));
      plannedPayment.setDate(repaymentPeriod.getEndDateAsString());
      balance = balance.add(costComponentsForRepaymentPeriod.getBalanceAdjustment());
      plannedPayment.setRemainingPrincipal(balance);
      plannedPayments.add(plannedPayment);
    }
    return plannedPayments;
  }

  private static Period getPeriodFromScheduledCharge(final ScheduledCharge scheduledCharge) {
    final ScheduledAction scheduledAction = scheduledCharge.getScheduledAction();
    if (ScheduledActionHelpers.actionHasNoActionPeriod(scheduledAction.action))
      return new Period(null, null);
    else
      return scheduledAction.repaymentPeriod;
  }
}