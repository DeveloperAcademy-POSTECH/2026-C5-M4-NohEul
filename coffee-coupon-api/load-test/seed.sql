INSERT INTO coupon_template (name, discount_rate) VALUES ('부하테스트용 아메리카노 10% 할인', 10);
SET @template_id = LAST_INSERT_ID();
INSERT INTO coupon_campaign (coupon_template_id, total_quantity, issued_quantity, open_at, version)
VALUES (@template_id, 2000, 0, DATE_SUB(NOW(), INTERVAL 1 MINUTE), 0);
SELECT LAST_INSERT_ID() AS campaign_id;
