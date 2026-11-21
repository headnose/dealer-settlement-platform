-- Wire templates, one row per template revision (blueprint: Wire Template)
CREATE TABLE wire_template (
                               template_id      VARCHAR(20)  NOT NULL,
                               wire_type        VARCHAR(30)  NOT NULL,
                               template_name    VARCHAR(100) NOT NULL,
                               template_content TEXT         NOT NULL,
                               version          INT          NOT NULL,
                               CONSTRAINT pk_wire_template PRIMARY KEY (template_id),
                               CONSTRAINT uk_wire_template_type_version UNIQUE (wire_type, version)
);

-- Standing internal recipients for every wire (blueprint: Notification Distribution)
CREATE TABLE notification_distribution (
                                           distribution_id   VARCHAR(20)  NOT NULL,
                                           distribution_type VARCHAR(40)  NOT NULL,
                                           name              VARCHAR(100) NOT NULL,
                                           email             VARCHAR(254) NOT NULL,
                                           CONSTRAINT pk_notification_distribution PRIMARY KEY (distribution_id),
                                           CONSTRAINT uk_notification_distribution_type UNIQUE (distribution_type)
);

-- One row per generated wire (blueprint: Wire Submission)
CREATE TABLE wire_submission (
                                 submission_id VARCHAR(40) NOT NULL,
                                 loan_id       VARCHAR(40) NOT NULL,
                                 wire_type     VARCHAR(30) NOT NULL,
                                 status        VARCHAR(20) NOT NULL,
                                 created_time  DATETIME    NOT NULL,
                                 content       TEXT        NOT NULL,
                                 CONSTRAINT pk_wire_submission PRIMARY KEY (submission_id),
                                 INDEX idx_wire_submission_loan_id (loan_id)
);

-- The four notification recipients of each wire (blueprint: Wire Recipient)
CREATE TABLE wire_recipient (
                                recipient_id    VARCHAR(40)  NOT NULL,
                                submission_id   VARCHAR(40)  NOT NULL,
                                recipient_type  VARCHAR(40)  NOT NULL,
                                name            VARCHAR(100) NOT NULL,
                                email           VARCHAR(254) NOT NULL,
                                delivery_status VARCHAR(20)  NOT NULL,
                                last_updated    DATETIME     NULL,
                                CONSTRAINT pk_wire_recipient PRIMARY KEY (recipient_id),
                                CONSTRAINT fk_wire_recipient_submission
                                    FOREIGN KEY (submission_id) REFERENCES wire_submission (submission_id),
                                CONSTRAINT uk_wire_recipient_submission_type UNIQUE (submission_id, recipient_type)
);