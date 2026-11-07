INSERT INTO department VALUES ('HQ','Headquarters',NULL),('UNIT-A','Unit A','HQ'),('UNIT-B','Unit B','UNIT-A');
INSERT INTO goal (id,unit_id,title,owner,parent_goal_id,status,due_date)
SELECT 'G'||lpad(n::text,2,'0'), CASE WHEN n<=8 THEN 'HQ' WHEN n<=16 THEN 'UNIT-A' ELSE 'UNIT-B' END,
'Goal '||n, CASE WHEN n IN (1,2) THEN NULL ELSE 'Employee '||n END,
CASE WHEN n<=8 THEN NULL WHEN n<=16 THEN 'G01' ELSE 'G09' END,'ACTIVE',DATE '2026-12-31'
FROM generate_series(1,24) n;
INSERT INTO key_result (id,goal_id,title,target_value,actual_value,weight,fact_updated_on)
SELECT 'K'||lpad(n::text,2,'0'),'G'||lpad(n::text,2,'0'),'Key result '||n,100,50,
CASE WHEN n IN (4,5) THEN 90 ELSE 100 END,
CASE WHEN n=6 THEN DATE '2026-08-01' ELSE DATE '2026-10-19' END
FROM generate_series(1,24) n WHERE n<>3;
