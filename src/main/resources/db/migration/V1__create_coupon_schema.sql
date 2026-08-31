CREATE TABLE coupon (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        name VARCHAR(100) NOT NULL,
                        total_quantity INT NOT NULL,
                        remaining_quantity INT NOT NULL,
                        start_at DATETIME(6) NOT NULL,
                        end_at DATETIME(6) NOT NULL,
                        created_at DATETIME(6) NOT NULL,
                        PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE coupon_issue (
                              id BIGINT NOT NULL AUTO_INCREMENT,
                              coupon_id BIGINT NOT NULL,
                              user_id BIGINT NOT NULL,
                              issued_at DATETIME(6) NOT NULL,
                              PRIMARY KEY (id),
                              CONSTRAINT uk_coupon_issue_coupon_user
                                  UNIQUE (coupon_id, user_id),
                              CONSTRAINT fk_coupon_issue_coupon
                                  FOREIGN KEY (coupon_id)
                                      REFERENCES coupon (id)
) ENGINE = InnoDB;

CREATE INDEX idx_coupon_issue_user_id
    ON coupon_issue (user_id);