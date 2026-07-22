-- V9__add_briefing_email_enabled.sql
-- Adds briefing_email_enabled flag to users table.
-- Existing rows and new users default to TRUE (subscribed).

ALTER TABLE users
    ADD COLUMN briefing_email_enabled TINYINT(1) NOT NULL DEFAULT 1;
