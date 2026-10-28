package com.ipgo.server.product;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplenishmentSettingRepository extends JpaRepository<ReplenishmentSetting, Long> {
    List<ReplenishmentSetting> findByProductIdIn(Collection<Long> productIds);
}
