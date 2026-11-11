-- #168 유통기한 관리. 재고관리단위(LOT)마다 수불부의 유통기한을 저장한다. 비어 있으면 수불부에 없던 것이다.
-- 기존 행은 NULL로 남고, 되돌릴 때는 두 칸만 지우면 된다.
ALTER TABLE inventory_units ADD COLUMN expiry_date DATE;

-- 유통기한 임박 기준(일). 비어 있으면 기본 30일이다.
ALTER TABLE warehouses ADD COLUMN expiry_imminent_days INTEGER;
ALTER TABLE warehouses ADD CONSTRAINT ck_warehouses_expiry_imminent_days
    CHECK (expiry_imminent_days IS NULL OR expiry_imminent_days BETWEEN 1 AND 365);
