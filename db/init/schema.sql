CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS bookings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL,
  show_id UUID NOT NULL,
  status VARCHAR(32) NOT NULL,
  total_amount NUMERIC(10,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS booking_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
  seat_id VARCHAR(255) NOT NULL,
  price NUMERIC(10,2) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_bookings_status_created_at ON bookings(status, created_at);
CREATE INDEX IF NOT EXISTS idx_booking_items_booking_id ON booking_items(booking_id);

ALTER TABLE booking_items
ADD COLUMN seat_id VARCHAR(255) NOT NULL DEFAULT 'TEMP_SEAT';

ALTER TABLE booking_items
ADD COLUMN created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL;

ALTER TABLE booking_items DROP COLUMN IF EXISTS seat_number;