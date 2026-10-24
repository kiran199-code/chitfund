-- Behavioural check of the chit schema (V1 to V6). Run against a scratch database after Flyway has migrated it:
--   psql -v ON_ERROR_STOP=1 -d <db> -f scripts/verify_schema.sql
-- Every "must fail" statement is run inside a sub-block; if it succeeds the script stops with a message.
-- Everything is rolled back at the end, so it leaves no data behind. Do not run against a database holding real data.
SET search_path = chit;
BEGIN;

CREATE FUNCTION pg_temp.must_fail(label text, stmt text) RETURNS void AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ok    (refused) %', label;
        RETURN;
    END;
    RAISE EXCEPTION 'NOT REFUSED: %', label;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.must_pass(label text, stmt text) RETURNS void AS $$
BEGIN
    EXECUTE stmt;
    RAISE NOTICE 'ok    (allowed) %', label;
END $$ LANGUAGE plpgsql;

-- groups -------------------------------------------------------------------------------------------------------------
SELECT pg_temp.must_fail('cycles must equal tickets',
  $$INSERT INTO chit_group(group_code,name,foreman_ref,chit_value,ticket_count,cycle_count,subscription,start_date,created_by)
    VALUES ('G-BAD1','x','F1',20000,2,3,10000,'2026-11-01','t')$$);
SELECT pg_temp.must_fail('subscription x tickets must equal chit value',
  $$INSERT INTO chit_group(group_code,name,foreman_ref,chit_value,ticket_count,cycle_count,subscription,start_date,created_by)
    VALUES ('G-BAD2','x','F1',20000,2,2,9999,'2026-11-01','t')$$);
INSERT INTO chit_group(group_code,name,foreman_ref,chit_value,ticket_count,cycle_count,subscription,start_date,created_by)
  VALUES ('G1','Group one','F1',20000,2,2,10000,'2026-11-01','t'),
         ('G2','Group two','F1',20000,2,2,10000,'2026-11-01','t');
SELECT pg_temp.must_fail('duplicate group code',
  $$INSERT INTO chit_group(group_code,name,foreman_ref,chit_value,ticket_count,cycle_count,subscription,start_date,created_by)
    VALUES ('G1','x','F1',20000,2,2,10000,'2026-11-01','t')$$);

-- rules versions ----------------------------------------------------------------------------------------------------
INSERT INTO chit_group_rules_version(group_id,version_no,rules_json,created_by)
  SELECT id,1,'{}','t' FROM chit_group WHERE group_code='G1';
SELECT pg_temp.must_pass('edit rules while not frozen',
  $$UPDATE chit_group_rules_version SET rules_json='{"a":1}' WHERE version_no=1$$);
UPDATE chit_group_rules_version SET frozen=true, frozen_at=now();
SELECT pg_temp.must_fail('edit frozen rules', $$UPDATE chit_group_rules_version SET rules_json='{"a":2}'$$);
SELECT pg_temp.must_fail('delete frozen rules', $$DELETE FROM chit_group_rules_version$$);

-- tickets, links, cycles ---------------------------------------------------------------------------------------------
INSERT INTO chit_ticket(group_id,ticket_no,customer_id,created_by)
  SELECT g.id,n,100+n,'t' FROM chit_group g, generate_series(1,2) n WHERE g.group_code IN ('G1','G2');
SELECT pg_temp.must_fail('duplicate ticket number in a group',
  $$INSERT INTO chit_ticket(group_id,ticket_no,customer_id,created_by) SELECT id,1,999,'t' FROM chit_group WHERE group_code='G1'$$);
INSERT INTO chit_subscriber_link(ticket_id,customer_id,valid_from,created_by)
  SELECT id,customer_id,'2026-11-01','t' FROM chit_ticket;
SELECT pg_temp.must_fail('two open subscriber links on one ticket',
  $$INSERT INTO chit_subscriber_link(ticket_id,customer_id,valid_from,created_by) SELECT id,555,'2026-11-02','t' FROM chit_ticket WHERE ticket_no=1 LIMIT 1$$);
INSERT INTO chit_cycle(group_id,cycle_no,due_date,auction_date)
  SELECT g.id,n,'2026-11-05','2026-11-06' FROM chit_group g, generate_series(1,2) n WHERE g.group_code='G1';
SELECT pg_temp.must_fail('auction before due date',
  $$INSERT INTO chit_cycle(group_id,cycle_no,due_date,auction_date) SELECT id,3,'2026-12-05','2026-12-04' FROM chit_group WHERE group_code='G1'$$);
UPDATE chit_cycle SET winner_ticket_id=(SELECT t.id FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1) WHERE cycle_no=1;
SELECT pg_temp.must_fail('the same ticket wins two cycles',
  $$UPDATE chit_cycle SET winner_ticket_id=(SELECT t.id FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1) WHERE cycle_no=2$$);

-- demands and payouts -----------------------------------------------------------------------------------------------
INSERT INTO chit_demand(demand_ref,cycle_id,ticket_id,due_date,raised_on,subscription_amount)
  SELECT 'D-1',c.id,t.id,c.due_date,c.due_date,10000 FROM chit_cycle c, chit_ticket t, chit_group g
   WHERE c.cycle_no=1 AND g.id=c.group_id AND g.group_code='G1' AND t.group_id=g.id AND t.ticket_no=1;
SELECT pg_temp.must_fail('overpay a demand',
  $$UPDATE chit_demand SET subscription_paid=10000.01 WHERE demand_ref='D-1'$$);
SELECT pg_temp.must_fail('dividend adjustment above the subscription',
  $$UPDATE chit_demand SET dividend_adjustment=10000.01 WHERE demand_ref='D-1'$$);
SELECT pg_temp.must_pass('pay exactly what is due net of dividend',
  $$UPDATE chit_demand SET dividend_adjustment=500, subscription_paid=9500 WHERE demand_ref='D-1'$$);

INSERT INTO chit_prize_payout(cycle_id,ticket_id,gross_prize,net_amount,made_by)
  SELECT c.id,c.winner_ticket_id,18000,18000,'maker' FROM chit_cycle c WHERE c.cycle_no=1;
-- Maker-checker on a payout is enforced by the service (CHIT_MAKER_CHECKER_ENFORCE), not by the database, since V6, so that a
-- single-person demo set-up can switch it off. The database still insists on a recorded checker once a payout is decided.
SELECT pg_temp.must_fail('approved with no checker',
  $$UPDATE chit_prize_payout SET status='APPROVED'$$);
SELECT pg_temp.must_fail('net + hold must equal gross',
  $$UPDATE chit_prize_payout SET hold_amount=1$$);
SELECT pg_temp.must_pass('another person approves',
  $$UPDATE chit_prize_payout SET status='APPROVED', checked_by='checker'$$);

-- ledger -----------------------------------------------------------------------------------------------------------
INSERT INTO ledger_account(group_id,account_type,normal_side)
  SELECT id,'POOL_CASH','D' FROM chit_group WHERE group_code='G1'
  UNION ALL SELECT id,'CHIT_POOL','C' FROM chit_group WHERE group_code='G1'
  UNION ALL SELECT id,'POOL_CASH','D' FROM chit_group WHERE group_code='G2';
INSERT INTO ledger_account(group_id,ticket_id,account_type,normal_side)
  SELECT t.group_id,t.id,'SUBSCRIBER_RECEIVABLE','D' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1;
SELECT pg_temp.must_fail('duplicate group-level account',
  $$INSERT INTO ledger_account(group_id,account_type,normal_side) SELECT id,'POOL_CASH','D' FROM chit_group WHERE group_code='G1'$$);
SELECT pg_temp.must_fail('per-ticket account without a ticket',
  $$INSERT INTO ledger_account(group_id,account_type,normal_side) SELECT id,'SUBSCRIBER_RECEIVABLE','D' FROM chit_group WHERE group_code='G1'$$);
SELECT pg_temp.must_fail('group-level account with a ticket',
  $$INSERT INTO ledger_account(group_id,ticket_id,account_type,normal_side) SELECT t.group_id,t.id,'FOREMAN_CASH','D' FROM chit_ticket t LIMIT 1$$);
SELECT pg_temp.must_fail('unknown account type',
  $$INSERT INTO ledger_account(group_id,account_type,normal_side) SELECT id,'MAGIC','D' FROM chit_group WHERE group_code='G1'$$);

-- A balanced journal: demand, Dr SUBSCRIBER_RECEIVABLE 10000 / Cr CHIT_POOL 10000.
INSERT INTO ledger_journal(group_id,template,posting_key,business_date,created_by)
  SELECT id,'DEMAND','DEMAND:G1:1:1','2026-11-05','t' FROM chit_group WHERE group_code='G1';
INSERT INTO ledger_line(journal_id,line_no,account_id,side,amount)
  SELECT j.id,1,a.id,'D',10000 FROM ledger_journal j, ledger_account a WHERE j.posting_key='DEMAND:G1:1:1' AND a.account_type='SUBSCRIBER_RECEIVABLE'
  UNION ALL
  SELECT j.id,2,a.id,'C',10000 FROM ledger_journal j, ledger_account a WHERE j.posting_key='DEMAND:G1:1:1' AND a.account_type='CHIT_POOL';
SET CONSTRAINTS ALL IMMEDIATE;
-- (the SET CONSTRAINTS ALL IMMEDIATE above ran the balance check on the journal and passed)
SET CONSTRAINTS ALL DEFERRED;
SELECT pg_temp.must_fail('zero or negative line', $$INSERT INTO ledger_line(journal_id,line_no,account_id,side,amount)
    SELECT journal_id,9,account_id,'D',0 FROM ledger_line LIMIT 1$$);
SELECT pg_temp.must_fail('duplicate posting key',
  $$INSERT INTO ledger_journal(group_id,template,posting_key,business_date,created_by) SELECT id,'DEMAND','DEMAND:G1:1:1','2026-11-05','t' FROM chit_group WHERE group_code='G1'$$);
SELECT pg_temp.must_fail('journal line on another group''s account',
  $$INSERT INTO ledger_line(journal_id,line_no,account_id,side,amount)
    SELECT j.id,3,a.id,'D',1 FROM ledger_journal j, ledger_account a, chit_group g
     WHERE j.posting_key='DEMAND:G1:1:1' AND a.group_id=g.id AND g.group_code='G2' LIMIT 1$$);
SELECT pg_temp.must_fail('update a ledger line', $$UPDATE ledger_line SET amount=1$$);
SELECT pg_temp.must_fail('delete a ledger line', $$DELETE FROM ledger_line$$);
SELECT pg_temp.must_fail('update a journal', $$UPDATE ledger_journal SET narration='x'$$);
SELECT pg_temp.must_fail('delete a journal', $$DELETE FROM ledger_journal$$);

-- An unbalanced journal must fail at commit time (constraints deferred, then forced).
INSERT INTO ledger_journal(group_id,template,posting_key,business_date,created_by)
  SELECT id,'DEMAND','UNBAL:1','2026-11-05','t' FROM chit_group WHERE group_code='G1';
INSERT INTO ledger_line(journal_id,line_no,account_id,side,amount)
  SELECT j.id,1,a.id,'D',10 FROM ledger_journal j, ledger_account a WHERE j.posting_key='UNBAL:1' AND a.account_type='POOL_CASH' AND a.group_id=j.group_id;
SELECT pg_temp.must_fail('unbalanced (one-sided) journal', $$SET CONSTRAINTS ALL IMMEDIATE$$);


-- V3: enrolment ---------------------------------------------------------------------------------------------------
-- a withdrawn ticket gives its number back; two live tickets still cannot share one
UPDATE chit_ticket SET status = 'EXITED', exit_reason = 'withdrawn', exited_on = '2026-11-02'
 WHERE group_id = (SELECT id FROM chit_group WHERE group_code = 'G2') AND ticket_no = 2;
SELECT pg_temp.must_pass('an exited ticket number can be taken again',
  $$INSERT INTO chit_ticket(group_id,ticket_no,customer_id,created_by) SELECT id,2,900,'t' FROM chit_group WHERE group_code='G2'$$);
SELECT pg_temp.must_fail('two live tickets with one number',
  $$INSERT INTO chit_ticket(group_id,ticket_no,customer_id,created_by) SELECT id,2,901,'t' FROM chit_group WHERE group_code='G2'$$);

INSERT INTO chit_fee_receipt(ticket_id,fee_kind,amount,received_on,created_by)
  SELECT t.id,'ENROLMENT',500,'2026-11-01','t' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1;
SELECT pg_temp.must_fail('the same fee twice for one ticket',
  $$INSERT INTO chit_fee_receipt(ticket_id,fee_kind,amount,received_on,created_by) SELECT t.id,'ENROLMENT',500,'2026-11-01','t' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1$$);
SELECT pg_temp.must_fail('a zero fee receipt',
  $$INSERT INTO chit_fee_receipt(ticket_id,fee_kind,amount,received_on,created_by) SELECT t.id,'REGISTRATION',0,'2026-11-01','t' FROM chit_ticket t LIMIT 1$$);
SELECT pg_temp.must_fail('refunded without a refund date', $$UPDATE chit_fee_receipt SET status = 'REFUNDED'$$);
SELECT pg_temp.must_pass('refunded with a date', $$UPDATE chit_fee_receipt SET status = 'REFUNDED', refunded_on = '2026-11-03'$$);

INSERT INTO chit_security(ticket_id,kind,amount,created_by)
  SELECT t.id,'DEPOSIT',2000,'t' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1;
SELECT pg_temp.must_fail('two live deposits for one ticket',
  $$INSERT INTO chit_security(ticket_id,kind,amount,created_by) SELECT t.id,'DEPOSIT',2000,'t' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1$$);
SELECT pg_temp.must_fail('verified with nobody recorded as the verifier', $$UPDATE chit_security SET status = 'VERIFIED'$$);
SELECT pg_temp.must_pass('verified by a named person', $$UPDATE chit_security SET status = 'VERIFIED', verified_by = 'ops1', verified_at = now()$$);
SELECT pg_temp.must_pass('a returned deposit frees the slot for a new one',
  $$UPDATE chit_security SET status = 'RETURNED', returned_on = '2026-11-04'$$);
SELECT pg_temp.must_pass('a new deposit after the return',
  $$INSERT INTO chit_security(ticket_id,kind,amount,created_by) SELECT t.id,'DEPOSIT',2000,'t' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1$$);

-- V4: collection ---------------------------------------------------------------------------------------------------
SELECT pg_temp.must_fail('PAID while a late fee is still unpaid',
  $$UPDATE chit_demand SET late_fee_accrued = 50, status = 'PAID' WHERE demand_ref = 'D-1'$$);
SELECT pg_temp.must_fail('PAID with the subscription not fully paid',
  $$UPDATE chit_demand SET subscription_paid = 9000, status = 'PAID' WHERE demand_ref = 'D-1'$$);
SELECT pg_temp.must_pass('PAID when subscription (net of dividend) and fees are settled',
  $$UPDATE chit_demand SET status = 'PAID' WHERE demand_ref = 'D-1'$$);
SELECT pg_temp.must_fail('a demand without the date it was raised',
  $$INSERT INTO chit_demand(demand_ref,cycle_id,ticket_id,due_date,raised_on,subscription_amount) SELECT 'D-NULL',cycle_id,ticket_id,due_date,NULL,1 FROM chit_demand WHERE demand_ref='D-1'$$);

INSERT INTO chit_payment(payment_ref,group_id,ticket_id,amount,channel,business_date,idempotency_key,created_by)
  SELECT 'RCPT-V4',g.id,t.id,300,'UPI','2026-11-05','v4-key','t' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1;
INSERT INTO chit_payment_allocation(payment_id,demand_id,bucket,amount)
  SELECT p.id,NULL,'ADVANCE',100 FROM chit_payment p WHERE p.payment_ref='RCPT-V4';
INSERT INTO chit_payment_allocation(payment_id,demand_id,bucket,amount)
  SELECT p.id,d.id,'CURRENT',200 FROM chit_payment p, chit_demand d WHERE p.payment_ref='RCPT-V4' AND d.demand_ref='D-1';
SELECT pg_temp.must_fail('more advance used than the advance row holds',
  $$UPDATE chit_payment_allocation SET advance_used = 100.01 WHERE bucket = 'ADVANCE'$$);
SELECT pg_temp.must_pass('advance partly used',
  $$UPDATE chit_payment_allocation SET advance_used = 40 WHERE bucket = 'ADVANCE'$$);
SELECT pg_temp.must_fail('advance_used on a row that is not advance',
  $$UPDATE chit_payment_allocation SET advance_used = 1 WHERE bucket = 'CURRENT'$$);
SELECT pg_temp.must_fail('from_advance on the advance row itself',
  $$UPDATE chit_payment_allocation SET from_advance = TRUE WHERE bucket = 'ADVANCE'$$);
SELECT pg_temp.must_pass('from_advance on a CURRENT row against a demand',
  $$UPDATE chit_payment_allocation SET from_advance = TRUE WHERE bucket = 'CURRENT'$$);
SELECT pg_temp.must_fail('from_advance on a LATE_FEES row',
  $$INSERT INTO chit_payment_allocation(payment_id,demand_id,bucket,amount,from_advance) SELECT p.id,d.id,'LATE_FEES',1,TRUE FROM chit_payment p, chit_demand d WHERE p.payment_ref='RCPT-V4' AND d.demand_ref='D-1'$$);

-- business date and holidays (V5) --------------------------------------------------------------------------------------
SELECT pg_temp.must_pass('the business date row is seeded', $$SELECT 1 FROM chit_business_date WHERE id = 1 AND status = 'OPEN'$$);
SELECT pg_temp.must_fail('a second business date row',
  $$INSERT INTO chit_business_date(id,business_date) VALUES (2,'2026-11-01')$$);
SELECT pg_temp.must_fail('a business date status that is not OPEN or EOD_RUNNING',
  $$UPDATE chit_business_date SET status = 'DONE' WHERE id = 1$$);
SELECT pg_temp.must_pass('end of day running', $$UPDATE chit_business_date SET status = 'EOD_RUNNING' WHERE id = 1$$);
SELECT pg_temp.must_pass('a holiday', $$INSERT INTO chit_holiday(holiday_date,name,created_by) VALUES ('2026-11-05','Diwali','t')$$);
SELECT pg_temp.must_fail('the same holiday twice', $$INSERT INTO chit_holiday(holiday_date,name,created_by) VALUES ('2026-11-05','Again','t')$$);
SELECT pg_temp.must_fail('a holiday with a blank name', $$INSERT INTO chit_holiday(holiday_date,name,created_by) VALUES ('2026-11-06','   ','t')$$);

-- auctions, bids, draws, payouts and covers (V6) ---------------------------------------------------------------------------
INSERT INTO chit_auction(cycle_id,auction_type,opened_by)
  SELECT c.id,'OPEN_ASCENDING_DISCOUNT','t' FROM chit_cycle c JOIN chit_group g ON g.id=c.group_id WHERE g.group_code='G1' AND c.cycle_no=2;
SELECT pg_temp.must_fail('a second live auction for one cycle',
  $$INSERT INTO chit_auction(cycle_id,auction_type,opened_by) SELECT c.id,'SEALED_BID','t' FROM chit_cycle c JOIN chit_group g ON g.id=c.group_id WHERE g.group_code='G1' AND c.cycle_no=2$$);
SELECT pg_temp.must_fail('a closed auction without a close basis',
  $$UPDATE chit_auction SET status='CLOSED', closed_at=now(), closed_by='t'$$);
SELECT pg_temp.must_fail('a close basis that is not one of the known ones',
  $$UPDATE chit_auction SET status='CLOSED', closed_at=now(), closed_by='t', close_basis='COIN_TOSS'$$);
SELECT pg_temp.must_pass('a rolled-over attempt',
  $$UPDATE chit_auction SET status='ROLLED_OVER', closed_at=now(), closed_by='t', close_basis='ROLLED_OVER'$$);
SELECT pg_temp.must_pass('a new live attempt once the earlier one rolled over',
  $$INSERT INTO chit_auction(cycle_id,auction_type,attempt,opened_by) SELECT c.id,'OPEN_ASCENDING_DISCOUNT',2,'t' FROM chit_cycle c JOIN chit_group g ON g.id=c.group_id WHERE g.group_code='G1' AND c.cycle_no=2$$);

INSERT INTO chit_draw_record(group_id,cycle_id,purpose,algorithm,seed,candidates,result,created_by)
  SELECT g.id,c.id,'TIE_BREAK','SHA256-MOD-V1',-7,'2,3','3','t' FROM chit_group g JOIN chit_cycle c ON c.group_id=g.id WHERE g.group_code='G1' AND c.cycle_no=2;
SELECT pg_temp.must_fail('two draws for one purpose in a cycle',
  $$INSERT INTO chit_draw_record(group_id,cycle_id,purpose,algorithm,seed,candidates,result,created_by) SELECT g.id,c.id,'TIE_BREAK','SHA256-MOD-V1',5,'2,3','2','t' FROM chit_group g JOIN chit_cycle c ON c.group_id=g.id WHERE g.group_code='G1' AND c.cycle_no=2$$);
INSERT INTO chit_draw_record(group_id,purpose,algorithm,seed,candidates,result,created_by)
  SELECT id,'FIXED_ORDER','SHA256-MOD-V1',9,'1,2','2,1','t' FROM chit_group WHERE group_code='G1';
SELECT pg_temp.must_fail('a second fixed order for one group',
  $$INSERT INTO chit_draw_record(group_id,purpose,algorithm,seed,candidates,result,created_by) SELECT id,'FIXED_ORDER','SHA256-MOD-V1',10,'1,2','1,2','t' FROM chit_group WHERE group_code='G1'$$);

SELECT pg_temp.must_fail('paid more than the prize',
  $$UPDATE chit_prize_payout SET paid_gross = 18000.01$$);
SELECT pg_temp.must_fail('held back more than was paid',
  $$UPDATE chit_prize_payout SET paid_gross = 100, paid_hold = 101$$);
SELECT pg_temp.must_fail('float repaid beyond what was floated',
  $$UPDATE chit_prize_payout SET float_amount = 100, float_repaid = 101$$);
SELECT pg_temp.must_fail('disbursed with part of the prize unpaid',
  $$UPDATE chit_prize_payout SET status='DISBURSED', disbursed_at=now(), paid_gross=17000$$);
SELECT pg_temp.must_pass('part paid',
  $$UPDATE chit_prize_payout SET status='PART_PAID', paid_gross=17000$$);
SELECT pg_temp.must_pass('disbursed once the whole prize is paid',
  $$UPDATE chit_prize_payout SET status='DISBURSED', disbursed_at=now(), paid_gross=18000$$);

SELECT pg_temp.must_pass('a payout leg',
  $$INSERT INTO chit_payout_leg(payout_id,leg_no,kind,amount,hold_amount,business_date,created_by) SELECT id,1,'DISBURSEMENT',17000,0,'2026-11-06','t' FROM chit_prize_payout$$);
SELECT pg_temp.must_fail('the same leg number twice',
  $$INSERT INTO chit_payout_leg(payout_id,leg_no,kind,amount,business_date,created_by) SELECT id,1,'DISBURSEMENT',1,'2026-11-06','t' FROM chit_prize_payout$$);
SELECT pg_temp.must_fail('a leg of nothing',
  $$INSERT INTO chit_payout_leg(payout_id,leg_no,kind,amount,business_date,created_by) SELECT id,2,'DISBURSEMENT',0,'2026-11-06','t' FROM chit_prize_payout$$);
SELECT pg_temp.must_fail('a leg holding back more than it pays',
  $$INSERT INTO chit_payout_leg(payout_id,leg_no,kind,amount,hold_amount,business_date,created_by) SELECT id,2,'DISBURSEMENT',10,11,'2026-11-06','t' FROM chit_prize_payout$$);
SELECT pg_temp.must_fail('a leg of an unknown kind',
  $$INSERT INTO chit_payout_leg(payout_id,leg_no,kind,amount,business_date,created_by) SELECT id,2,'GIFT',10,'2026-11-06','t' FROM chit_prize_payout$$);

SELECT pg_temp.must_pass('an internal payment (foreman cover or dividend credit)',
  $$INSERT INTO chit_payment(payment_ref,group_id,ticket_id,amount,channel,business_date,idempotency_key,created_by) SELECT 'RCPT-INT',g.id,t.id,100,'INTERNAL','2026-11-06','int-1','t' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1$$);
SELECT pg_temp.must_fail('a payment channel that is not known',
  $$INSERT INTO chit_payment(payment_ref,group_id,ticket_id,amount,channel,business_date,idempotency_key,created_by) SELECT 'RCPT-CHQ',g.id,t.id,100,'CHEQUE','2026-11-06','chq-1','t' FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code='G1' AND t.ticket_no=1$$);

INSERT INTO chit_foreman_cover(cycle_id,ticket_id,demand_id,payment_id,amount,covered_on,created_by)
  SELECT d.cycle_id,d.ticket_id,d.id,p.id,100,'2026-11-06','t' FROM chit_demand d, chit_payment p WHERE d.demand_ref='D-1' AND p.payment_ref='RCPT-INT';
SELECT pg_temp.must_fail('two covers for one demand',
  $$INSERT INTO chit_foreman_cover(cycle_id,ticket_id,demand_id,payment_id,amount,covered_on,created_by) SELECT d.cycle_id,d.ticket_id,d.id,p.id,50,'2026-11-06','t' FROM chit_demand d, chit_payment p WHERE d.demand_ref='D-1' AND p.payment_ref='RCPT-INT'$$);
SELECT pg_temp.must_fail('more recovered than was covered',
  $$UPDATE chit_foreman_cover SET recovered_amount = 100.01$$);
SELECT pg_temp.must_pass('recovered in full',
  $$UPDATE chit_foreman_cover SET recovered_amount = 100, status='RECOVERED'$$);

ROLLBACK;
\echo 'schema verification finished: every rule above was exercised'
