-- ============================================================
-- BELL PHONE LAUNCH DAY - ORACLE DATABASE SCHEMA
-- ============================================================
-- Optimized for high-concurrency reservations with no overselling
-- ============================================================

-- Drop existing tables and sequences (if exists)
BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE RESERVATION_AUDIT';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE PURCHASE_HISTORY';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE RESERVATIONS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE INVENTORY';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE PRODUCTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP SEQUENCE SEQ_RESERVATION_ID';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP SEQUENCE SEQ_PURCHASE_ID';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- ============================================================
-- SEQUENCES
-- ============================================================
CREATE SEQUENCE SEQ_RESERVATION_ID START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE SEQ_PURCHASE_ID START WITH 1 INCREMENT BY 1 NOCACHE;

-- ============================================================
-- PRODUCTS TABLE
-- ============================================================
-- Stores product SKU: iPhone 16 Pro, 256GB, Space Black, etc.
CREATE TABLE PRODUCTS (
    PRODUCT_ID          NUMBER PRIMARY KEY,
    MODEL_NAME          VARCHAR2(100) NOT NULL,
    COLOR               VARCHAR2(50) NOT NULL,
    STORAGE_GB          NUMBER NOT NULL,
    PRICE               NUMBER(10,2) NOT NULL,
    SKU                 VARCHAR2(50) UNIQUE NOT NULL,
    LAUNCH_DATE         DATE DEFAULT SYSDATE,
    CREATED_AT          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_PRODUCTS PRIMARY KEY (PRODUCT_ID)
);

CREATE UNIQUE INDEX IDX_PRODUCTS_SKU ON PRODUCTS(SKU);

-- ============================================================
-- INVENTORY TABLE
-- Core table for stock management
-- Uses row-level locking for atomic decrement operations
-- ============================================================
CREATE TABLE INVENTORY (
    INVENTORY_ID        NUMBER PRIMARY KEY,
    PRODUCT_ID          NUMBER NOT NULL,
    TOTAL_UNITS         NUMBER NOT NULL,
    AVAILABLE_UNITS     NUMBER NOT NULL,
    RESERVED_UNITS      NUMBER DEFAULT 0,
    SOLD_UNITS          NUMBER DEFAULT 0,
    LAST_UPDATED        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    VERSION             NUMBER DEFAULT 1,
    CONSTRAINT FK_INVENTORY_PRODUCT FOREIGN KEY (PRODUCT_ID) 
        REFERENCES PRODUCTS(PRODUCT_ID)
);

CREATE INDEX IDX_INVENTORY_PRODUCT ON INVENTORY(PRODUCT_ID);
CREATE INDEX IDX_INVENTORY_AVAILABLE ON INVENTORY(AVAILABLE_UNITS);

-- ============================================================
-- RESERVATIONS TABLE
-- Holds reserved phones during checkout
-- Time-based expiry (expires after RESERVATION_TIMEOUT_MINS)
-- ============================================================
CREATE TABLE RESERVATIONS (
    RESERVATION_ID      NUMBER PRIMARY KEY,
    PRODUCT_ID          NUMBER NOT NULL,
    CUSTOMER_ID         VARCHAR2(100) NOT NULL,
    QUANTITY            NUMBER DEFAULT 1,
    STATUS              VARCHAR2(20) DEFAULT 'ACTIVE',
    RESERVED_AT         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    EXPIRES_AT          TIMESTAMP,
    COMPLETED_AT        TIMESTAMP,
    SESSION_TOKEN       VARCHAR2(500),
    DEVICE_FINGERPRINT  VARCHAR2(500),
    CONSTRAINT FK_RES_PRODUCT FOREIGN KEY (PRODUCT_ID) 
        REFERENCES PRODUCTS(PRODUCT_ID),
    CONSTRAINT CHK_RES_STATUS CHECK (STATUS IN ('ACTIVE', 'COMPLETED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT CHK_RES_QUANTITY CHECK (QUANTITY > 0)
);

CREATE INDEX IDX_RES_CUSTOMER ON RESERVATIONS(CUSTOMER_ID);
CREATE INDEX IDX_RES_PRODUCT ON RESERVATIONS(PRODUCT_ID);
CREATE INDEX IDX_RES_STATUS ON RESERVATIONS(STATUS);
CREATE INDEX IDX_RES_EXPIRES ON RESERVATIONS(EXPIRES_AT);
CREATE INDEX IDX_RES_SESSION ON RESERVATIONS(SESSION_TOKEN);

-- ============================================================
-- PURCHASE_HISTORY TABLE
-- Final purchase records (immutable log)
-- ============================================================
CREATE TABLE PURCHASE_HISTORY (
    PURCHASE_ID         NUMBER PRIMARY KEY,
    RESERVATION_ID      NUMBER,
    PRODUCT_ID          NUMBER NOT NULL,
    CUSTOMER_ID         VARCHAR2(100) NOT NULL,
    QUANTITY            NUMBER DEFAULT 1,
    UNIT_PRICE          NUMBER(10,2) NOT NULL,
    TOTAL_PRICE         NUMBER(10,2) NOT NULL,
    STATUS              VARCHAR2(20) DEFAULT 'COMPLETED',
    PURCHASED_AT        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT FK_PH_PRODUCT FOREIGN KEY (PRODUCT_ID) 
        REFERENCES PRODUCTS(PRODUCT_ID),
    CONSTRAINT FK_PH_RESERVATION FOREIGN KEY (RESERVATION_ID) 
        REFERENCES RESERVATIONS(RESERVATION_ID)
);

CREATE INDEX IDX_PH_CUSTOMER ON PURCHASE_HISTORY(CUSTOMER_ID);
CREATE INDEX IDX_PH_PRODUCT ON PURCHASE_HISTORY(PRODUCT_ID);
CREATE INDEX IDX_PH_PURCHASED_AT ON PURCHASE_HISTORY(PURCHASED_AT);

-- ============================================================
-- AUDIT TABLE
-- For compliance and debugging
-- ============================================================
CREATE TABLE RESERVATION_AUDIT (
    AUDIT_ID            NUMBER PRIMARY KEY,
    RESERVATION_ID      NUMBER,
    ACTION              VARCHAR2(100),
    BEFORE_STATE        VARCHAR2(1000),
    AFTER_STATE         VARCHAR2(1000),
    ACTOR               VARCHAR2(100),
    CREATED_AT          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- STORED PROCEDURE: RESERVE PHONE
-- Atomically reserves a phone or throws exception
-- Uses SELECT FOR UPDATE to prevent race conditions
-- ============================================================
CREATE OR REPLACE PROCEDURE RESERVE_PHONE(
    p_product_id      IN  NUMBER,
    p_customer_id     IN  VARCHAR2,
    p_quantity        IN  NUMBER,
    p_session_token   IN  VARCHAR2,
    p_device_fp       IN  VARCHAR2,
    o_reservation_id  OUT NUMBER,
    o_success         OUT BOOLEAN
)
IS
    v_available     NUMBER;
    v_reserved_count NUMBER;
BEGIN
    o_success := FALSE;
    
    -- Lock the inventory row for this product
    -- This prevents concurrent overselling
    BEGIN
        SELECT AVAILABLE_UNITS INTO v_available
        FROM INVENTORY
        WHERE PRODUCT_ID = p_product_id
        FOR UPDATE NOWAIT;
        
    EXCEPTION
        WHEN OTHERS THEN
            -- Row is locked by another transaction
            RAISE_APPLICATION_ERROR(-20001, 'INVENTORY_LOCKED');
    END;
    
    -- Check if enough inventory
    IF v_available < p_quantity THEN
        RAISE_APPLICATION_ERROR(-20002, 'INSUFFICIENT_INVENTORY');
    END IF;
    
    -- Check customer doesn't have too many active reservations (fraud prevention)
    SELECT COUNT(*) INTO v_reserved_count
    FROM RESERVATIONS
    WHERE CUSTOMER_ID = p_customer_id
    AND STATUS = 'ACTIVE'
    AND EXPIRES_AT > CURRENT_TIMESTAMP;
    
    IF v_reserved_count > 5 THEN
        RAISE_APPLICATION_ERROR(-20003, 'TOO_MANY_ACTIVE_RESERVATIONS');
    END IF;
    
    -- Create reservation
    o_reservation_id := SEQ_RESERVATION_ID.NEXTVAL;
    
    INSERT INTO RESERVATIONS (
        RESERVATION_ID, PRODUCT_ID, CUSTOMER_ID, QUANTITY, 
        STATUS, EXPIRES_AT, SESSION_TOKEN, DEVICE_FINGERPRINT
    ) VALUES (
        o_reservation_id, p_product_id, p_customer_id, p_quantity,
        'ACTIVE', CURRENT_TIMESTAMP + INTERVAL '15' MINUTE, 
        p_session_token, p_device_fp
    );
    
    -- Decrement available units
    UPDATE INVENTORY
    SET AVAILABLE_UNITS = AVAILABLE_UNITS - p_quantity,
        RESERVED_UNITS = RESERVED_UNITS + p_quantity,
        LAST_UPDATED = CURRENT_TIMESTAMP
    WHERE PRODUCT_ID = p_product_id;
    
    COMMIT;
    o_success := TRUE;
    
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END RESERVE_PHONE;
/

-- ============================================================
-- STORED PROCEDURE: COMPLETE PURCHASE
-- Atomically converts reservation to purchase
-- ============================================================
CREATE OR REPLACE PROCEDURE COMPLETE_PURCHASE(
    p_reservation_id  IN  NUMBER,
    o_purchase_id     OUT NUMBER,
    o_success         OUT BOOLEAN
)
IS
    v_product_id      NUMBER;
    v_customer_id     VARCHAR2(100);
    v_quantity        NUMBER;
    v_price           NUMBER;
BEGIN
    o_success := FALSE;
    
    -- Fetch and lock the reservation
    SELECT PRODUCT_ID, CUSTOMER_ID, QUANTITY
    INTO v_product_id, v_customer_id, v_quantity
    FROM RESERVATIONS
    WHERE RESERVATION_ID = p_reservation_id
    FOR UPDATE;
    
    -- Check reservation is still valid
    IF CURRENT_TIMESTAMP > (
        SELECT EXPIRES_AT FROM RESERVATIONS WHERE RESERVATION_ID = p_reservation_id
    ) THEN
        RAISE_APPLICATION_ERROR(-20004, 'RESERVATION_EXPIRED');
    END IF;
    
    -- Get current price
    SELECT PRICE INTO v_price
    FROM PRODUCTS
    WHERE PRODUCT_ID = v_product_id;
    
    -- Create purchase record
    o_purchase_id := SEQ_PURCHASE_ID.NEXTVAL;
    
    INSERT INTO PURCHASE_HISTORY (
        PURCHASE_ID, RESERVATION_ID, PRODUCT_ID, CUSTOMER_ID, 
        QUANTITY, UNIT_PRICE, TOTAL_PRICE, STATUS
    ) VALUES (
        o_purchase_id, p_reservation_id, v_product_id, v_customer_id,
        v_quantity, v_price, (v_price * v_quantity), 'COMPLETED'
    );
    
    -- Update reservation status
    UPDATE RESERVATIONS
    SET STATUS = 'COMPLETED', COMPLETED_AT = CURRENT_TIMESTAMP
    WHERE RESERVATION_ID = p_reservation_id;
    
    -- Update inventory: move from reserved to sold
    UPDATE INVENTORY
    SET RESERVED_UNITS = RESERVED_UNITS - v_quantity,
        SOLD_UNITS = SOLD_UNITS + v_quantity,
        LAST_UPDATED = CURRENT_TIMESTAMP
    WHERE PRODUCT_ID = v_product_id;
    
    COMMIT;
    o_success := TRUE;
    
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END COMPLETE_PURCHASE;
/

-- ============================================================
-- STORED PROCEDURE: RELEASE EXPIRED RESERVATIONS
-- Called periodically to free up reserved inventory
-- ============================================================
CREATE OR REPLACE PROCEDURE RELEASE_EXPIRED_RESERVATIONS
IS
BEGIN
    -- Update expired reservations
    UPDATE RESERVATIONS
    SET STATUS = 'EXPIRED'
    WHERE STATUS = 'ACTIVE'
    AND EXPIRES_AT < CURRENT_TIMESTAMP;
    
    -- Free up inventory for expired reservations
    FOR rec IN (
        SELECT PRODUCT_ID, SUM(QUANTITY) as qty
        FROM RESERVATIONS
        WHERE STATUS = 'EXPIRED'
        GROUP BY PRODUCT_ID
    )
    LOOP
        UPDATE INVENTORY
        SET AVAILABLE_UNITS = AVAILABLE_UNITS + rec.qty,
            RESERVED_UNITS = RESERVED_UNITS - rec.qty,
            LAST_UPDATED = CURRENT_TIMESTAMP
        WHERE PRODUCT_ID = rec.PRODUCT_ID;
    END LOOP;
    
    COMMIT;
END RELEASE_EXPIRED_RESERVATIONS;
/

-- ============================================================
-- SAMPLE DATA - iPhone 16 Pro Launch
-- ============================================================
BEGIN
    -- Insert products
    INSERT INTO PRODUCTS (PRODUCT_ID, MODEL_NAME, COLOR, STORAGE_GB, PRICE, SKU) 
    VALUES (1, 'iPhone 16 Pro', 'Space Black', 256, 1099.99, 'IP16P-SB-256');
    
    INSERT INTO PRODUCTS (PRODUCT_ID, MODEL_NAME, COLOR, STORAGE_GB, PRICE, SKU) 
    VALUES (2, 'iPhone 16 Pro', 'Space Black', 512, 1199.99, 'IP16P-SB-512');
    
    INSERT INTO PRODUCTS (PRODUCT_ID, MODEL_NAME, COLOR, STORAGE_GB, PRICE, SKU) 
    VALUES (3, 'iPhone 16 Pro', 'Gold', 256, 1099.99, 'IP16P-GD-256');
    
    INSERT INTO PRODUCTS (PRODUCT_ID, MODEL_NAME, COLOR, STORAGE_GB, PRICE, SKU) 
    VALUES (4, 'iPhone 16 Pro', 'Gold', 512, 1199.99, 'IP16P-GD-512');
    
    -- Insert inventory (10,000 units total across all variants)
    INSERT INTO INVENTORY (INVENTORY_ID, PRODUCT_ID, TOTAL_UNITS, AVAILABLE_UNITS) 
    VALUES (1, 1, 2500, 2500);
    
    INSERT INTO INVENTORY (INVENTORY_ID, PRODUCT_ID, TOTAL_UNITS, AVAILABLE_UNITS) 
    VALUES (2, 2, 2500, 2500);
    
    INSERT INTO INVENTORY (INVENTORY_ID, PRODUCT_ID, TOTAL_UNITS, AVAILABLE_UNITS) 
    VALUES (3, 3, 2500, 2500);
    
    INSERT INTO INVENTORY (INVENTORY_ID, PRODUCT_ID, TOTAL_UNITS, AVAILABLE_UNITS) 
    VALUES (4, 4, 2500, 2500);
    
    COMMIT;
END;
/

-- ============================================================
-- VIEWS FOR REPORTING
-- ============================================================
CREATE OR REPLACE VIEW V_INVENTORY_STATUS AS
SELECT 
    p.SKU,
    p.MODEL_NAME,
    p.COLOR,
    p.STORAGE_GB,
    i.TOTAL_UNITS,
    i.AVAILABLE_UNITS,
    i.RESERVED_UNITS,
    i.SOLD_UNITS,
    ROUND(100.0 * i.SOLD_UNITS / NULLIF(i.TOTAL_UNITS, 0), 2) as SOLD_PERCENTAGE
FROM PRODUCTS p
JOIN INVENTORY i ON p.PRODUCT_ID = i.PRODUCT_ID
ORDER BY p.SKU;

CREATE OR REPLACE VIEW V_REAL_TIME_STATUS AS
SELECT 
    SKU,
    AVAILABLE_UNITS,
    RESERVED_UNITS,
    SOLD_UNITS,
    (AVAILABLE_UNITS + RESERVED_UNITS + SOLD_UNITS) as TOTAL_ACCOUNTED
FROM V_INVENTORY_STATUS;

COMMIT;
