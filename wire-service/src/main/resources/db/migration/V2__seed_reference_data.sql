-- Settlement wire template, revision 1. Placeholders are filled by wire-service from
-- settlement-service's SettlementDetailDto ({{name}} = field name in that DTO).
INSERT INTO wire_template (template_id, wire_type, template_name, template_content, version)
VALUES ('TMP-001', 'SETTLEMENT', 'Settlement Wire Template',
        '<?xml version="1.0" encoding="UTF-8"?>
        <settlementWire>
            <loanId>{{loanId}}</loanId>
            <settlementId>{{settlementId}}</settlementId>
            <dealerName>{{dealerName}}</dealerName>
            <beneficiaryBank>{{beneficiaryBank}}</beneficiaryBank>
            <paymentMethod>{{paymentMethod}}</paymentMethod>
            <currency>{{currency}}</currency>
            <grossAmount>{{grossAmount}}</grossAmount>
            <totalAdjustment>{{totalAdjustment}}</totalAdjustment>
            <netSettlement>{{netSettlement}}</netSettlement>
            <valueDate>{{valueDate}}</valueDate>
        </settlementWire>',
        1);

-- Standing internal recipients added to every wire (neutral example data)
INSERT INTO notification_distribution (distribution_id, distribution_type, name, email)
VALUES ('AUDIT-001', 'AUDIT_DISTRIBUTION', 'Compliance Audit Team', 'compliance-audit@example.com'),
       ('OPS-001', 'INTERNAL_OPS_DISTRIBUTION', 'Treasury Operations', 'treasury-ops@example.com');