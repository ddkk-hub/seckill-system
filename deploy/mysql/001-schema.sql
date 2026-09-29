-- Only executed by MySQL on a NEW Docker data volume. Never runs against the host database.
CREATE TABLE product (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(255) NOT NULL,
 stock INT NOT NULL,
 price DECIMAL(10,2) NOT NULL,
 CONSTRAINT chk_product_stock CHECK (stock>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO product(id,name,stock,price) VALUES (1,'Docker demo iPhone',100,6999.00);

-- Execute against database seckill. Existing product rows are not modified.
-- Verify product uses InnoDB and id is its primary key before running the app.
SHOW CREATE TABLE product;
CREATE TABLE IF NOT EXISTS seckill_order (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    price DECIMAL(10,2) NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_order_product (product_id),
    CONSTRAINT chk_order_quantity CHECK (quantity = 1),
    CONSTRAINT chk_order_user CHECK (user_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Run once before stage2. Existing product and order rows are unchanged.
CREATE TABLE IF NOT EXISTS seckill_stock_baseline (
    product_id BIGINT NOT NULL PRIMARY KEY,
    initial_stock INT NOT NULL,
    initial_order_quantity BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_baseline_stock CHECK (initial_stock >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Consumer deduplication and final result; existing orders remain unchanged.
CREATE TABLE IF NOT EXISTS seckill_async_order (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    order_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_async_order (order_id),
    KEY idx_async_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
