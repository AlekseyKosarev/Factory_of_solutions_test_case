INSERT INTO quality_rule VALUES
('GOAL_NO_OWNER','Missing owner','critical','Active goal has no nonblank owner'),
('GOAL_NO_KR','Missing key results','critical','Active goal has zero key results'),
('KR_WEIGHTS_SUM','Invalid weights','major','Nonempty KR weights do not total 100'),
('KR_STALE_PROGRESS','Stale progress','minor','Fact date missing or older than 45 days');
