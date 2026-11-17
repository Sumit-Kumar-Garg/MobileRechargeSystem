package com.app.recharge.catalog;

final class PlanMapper {

    private PlanMapper() { }

    /** Converts a plan entity and its benefits into the immutable API view. */
    static PlanView toView(Plan p) {
        return new PlanView(
                p.getId(),
                p.getProviderId(),
                p.getCode(),
                p.getMarket(),
                p.getName(),
                new PlanView.Price(p.getPriceMinor(), p.getCurrency()),
                p.getValidityDays(),
                new PlanView.Allowances(p.getDataMb(), p.getVoiceMin(), p.getSms()),
                p.getBenefits().stream()
                        .map(b -> new PlanView.Benefit(b.getType(), b.getLabel()))
                        .toList());
    }
}
