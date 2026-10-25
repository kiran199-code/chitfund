-- Behavioural check of the portal schema (V1 and V2). Run against a scratch database after Flyway has migrated it:
--   psql -v ON_ERROR_STOP=1 -d <db> -f scripts/verify_schema.sql
-- Every "must fail" statement runs in a sub-block; if it succeeds the script stops. Everything is rolled back at the end.
-- Do not run against a database holding real data.
SET search_path = portal;
BEGIN;

CREATE FUNCTION pg_temp.must_fail(label text, stmt text) RETURNS void AS $$
BEGIN
    BEGIN EXECUTE stmt; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'ok    (refused) %', label; RETURN; END;
    RAISE EXCEPTION 'NOT REFUSED: %', label;
END $$ LANGUAGE plpgsql;
CREATE FUNCTION pg_temp.must_pass(label text, stmt text) RETURNS void AS $$
BEGIN EXECUTE stmt; RAISE NOTICE 'ok    (allowed) %', label; END $$ LANGUAGE plpgsql;

-- members
SELECT pg_temp.must_pass('a member', $$INSERT INTO portal_member(email,google_sub) VALUES ('a@x.com','s1')$$);
SELECT pg_temp.must_fail('upper-case email', $$INSERT INTO portal_member(email) VALUES ('B@x.com')$$);
SELECT pg_temp.must_fail('padded email', $$INSERT INTO portal_member(email) VALUES (' b@x.com')$$);
SELECT pg_temp.must_fail('not an address', $$INSERT INTO portal_member(email) VALUES ('nope')$$);
SELECT pg_temp.must_fail('duplicate email', $$INSERT INTO portal_member(email) VALUES ('a@x.com')$$);
SELECT pg_temp.must_fail('duplicate Google account', $$INSERT INTO portal_member(email,google_sub) VALUES ('c@x.com','s1')$$);
SELECT pg_temp.must_pass('two members with no Google link yet', $$INSERT INTO portal_member(email) VALUES ('d@x.com'),('e@x.com')$$);
SELECT pg_temp.must_fail('unknown member status', $$UPDATE portal_member SET status='GONE' WHERE email='d@x.com'$$);

-- invitations
SELECT pg_temp.must_pass('an invitation', $$INSERT INTO portal_invitation(group_code,group_name,email,token_hash,expires_at,created_by)
  VALUES ('G1','g','a@x.com',repeat('a',64),now()+interval '7 days','adm')$$);
SELECT pg_temp.must_fail('same token hash twice', $$INSERT INTO portal_invitation(group_code,group_name,email,token_hash,expires_at,created_by)
  VALUES ('G1','g','f@x.com',repeat('a',64),now()+interval '7 days','adm')$$);
SELECT pg_temp.must_fail('second live invitation for the same group and address', $$INSERT INTO portal_invitation(group_code,group_name,email,token_hash,expires_at,created_by)
  VALUES ('G1','g','a@x.com',repeat('b',64),now()+interval '7 days','adm')$$);
SELECT pg_temp.must_pass('same address in another group', $$INSERT INTO portal_invitation(group_code,group_name,email,token_hash,expires_at,created_by)
  VALUES ('G2','g','a@x.com',repeat('c',64),now()+interval '7 days','adm')$$);
SELECT pg_temp.must_fail('upper-case invitee email', $$INSERT INTO portal_invitation(group_code,group_name,email,token_hash,expires_at,created_by)
  VALUES ('G1','g','Z@x.com',repeat('d',64),now()+interval '7 days','adm')$$);
SELECT pg_temp.must_fail('zero tickets', $$INSERT INTO portal_invitation(group_code,group_name,email,tickets_offered,token_hash,expires_at,created_by)
  VALUES ('G1','g','g@x.com',0,repeat('e',64),now()+interval '7 days','adm')$$);
SELECT pg_temp.must_fail('unknown status', $$UPDATE portal_invitation SET status='LOST' WHERE group_code='G2'$$);
SELECT pg_temp.must_fail('DECLINED without a time', $$UPDATE portal_invitation SET status='DECLINED' WHERE group_code='G2'$$);
SELECT pg_temp.must_fail('a time without DECLINED', $$UPDATE portal_invitation SET declined_at=now() WHERE group_code='G2'$$);
SELECT pg_temp.must_fail('REVOKED without who', $$UPDATE portal_invitation SET status='REVOKED', revoked_at=now() WHERE group_code='G2'$$);
SELECT pg_temp.must_fail('ACCEPTED without a member', $$UPDATE portal_invitation SET status='ACCEPTED', accepted_at=now() WHERE group_code='G2'$$);
SELECT pg_temp.must_pass('REVOKED with who and when', $$UPDATE portal_invitation SET status='REVOKED', revoked_at=now(), revoked_by='adm' WHERE group_code='G2'$$);
SELECT pg_temp.must_pass('a new live invitation once the old one is revoked', $$INSERT INTO portal_invitation(group_code,group_name,email,token_hash,expires_at,created_by)
  VALUES ('G2','g','a@x.com',repeat('f',64),now()+interval '7 days','adm')$$);
SELECT pg_temp.must_fail('ACCEPTED without an enrolled ticket or customer (V2)', $$UPDATE portal_invitation SET status='ACCEPTED', accepted_at=now(), member_id=(SELECT id FROM portal_member WHERE email='a@x.com') WHERE group_code='G2' AND status='PENDING'$$);
SELECT pg_temp.must_fail('AWAITING_PAYMENT without a customer (V2)', $$UPDATE portal_invitation SET status='ACCEPTED_AWAITING_PAYMENT', accepted_at=now(), member_id=(SELECT id FROM portal_member WHERE email='a@x.com') WHERE group_code='G2' AND status='PENDING'$$);
SELECT pg_temp.must_pass('AWAITING_PAYMENT with member, time and customer (the status fits its column)', $$UPDATE portal_invitation SET status='ACCEPTED_AWAITING_PAYMENT', accepted_at=now(), customer_id=5001, member_id=(SELECT id FROM portal_member WHERE email='a@x.com') WHERE group_code='G2' AND status='PENDING'$$);
SELECT pg_temp.must_fail('more tickets enrolled than offered', $$UPDATE portal_invitation SET tickets_enrolled=2 WHERE group_code='G2' AND status='ACCEPTED_AWAITING_PAYMENT'$$);
SELECT pg_temp.must_fail('a non-positive existing customer id', $$UPDATE portal_invitation SET existing_customer_id=0 WHERE group_code='G2' AND status='ACCEPTED_AWAITING_PAYMENT'$$);
SELECT pg_temp.must_pass('a ticket row', $$INSERT INTO portal_invitation_ticket(invitation_id,seq,ticket_no) SELECT id,1,7 FROM portal_invitation WHERE group_code='G2' AND status='ACCEPTED_AWAITING_PAYMENT'$$);
SELECT pg_temp.must_fail('the same seq twice', $$INSERT INTO portal_invitation_ticket(invitation_id,seq,ticket_no) SELECT id,1,8 FROM portal_invitation WHERE group_code='G2' AND status='ACCEPTED_AWAITING_PAYMENT'$$);
SELECT pg_temp.must_pass('ACCEPTED with one ticket enrolled and a customer', $$UPDATE portal_invitation SET status='ACCEPTED', tickets_enrolled=1 WHERE group_code='G2' AND status='ACCEPTED_AWAITING_PAYMENT'$$);

-- sessions, outbox, idempotency
SELECT pg_temp.must_pass('a session', $$INSERT INTO portal_session(member_id,token_hash,expires_at) SELECT id,repeat('1',64),now()+interval '8 hours' FROM portal_member WHERE email='a@x.com'$$);
SELECT pg_temp.must_fail('same session hash twice', $$INSERT INTO portal_session(member_id,token_hash,expires_at) SELECT id,repeat('1',64),now()+interval '8 hours' FROM portal_member WHERE email='a@x.com'$$);
SELECT pg_temp.must_pass('an outbox row', $$INSERT INTO portal_outbox(kind,recipient_email,subject,body_text,dedupe_key) VALUES ('INVITATION','a@x.com','s','b','INVITE:1:1')$$);
SELECT pg_temp.must_fail('same dedupe key twice', $$INSERT INTO portal_outbox(kind,recipient_email,subject,body_text,dedupe_key) VALUES ('INVITATION','a@x.com','s','b','INVITE:1:1')$$);
SELECT pg_temp.must_fail('SENT without a time', $$UPDATE portal_outbox SET status='SENT'$$);
SELECT pg_temp.must_fail('a send time without SENT', $$UPDATE portal_outbox SET sent_at=now()$$);
SELECT pg_temp.must_fail('negative attempts', $$UPDATE portal_outbox SET attempts=-1$$);
SELECT pg_temp.must_pass('SENT with a time', $$UPDATE portal_outbox SET status='SENT', sent_at=now()$$);

ROLLBACK;
\echo 'portal schema verification finished: every rule above was exercised'
