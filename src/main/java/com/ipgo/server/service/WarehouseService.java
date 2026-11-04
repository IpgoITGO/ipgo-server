package com.ipgo.server.service;

import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.repository.WarehouseRepository;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 지금은 회원당 창고 1개(기본 창고)를 쓴다. 창고 선택은 아직 없다.
@Service
public class WarehouseService {
    static final String DEFAULT_WAREHOUSE_NAME = "기본 창고";

    private final WarehouseRepository warehouses;

    public WarehouseService(WarehouseRepository warehouses) {
        this.warehouses = warehouses;
    }

    @Transactional(readOnly = true)
    public Optional<Warehouse> findDefault(long userId) {
        return warehouses.findFirstByUserIdOrderByIdAsc(userId);
    }

    // 회원의 첫 창고를 잠가서 돌려주고, 없으면 기본 창고를 만든다. 같은 창고에 대한 동시 업로드는 차례로 처리된다.
    @Transactional
    public Warehouse lockDefault(long userId) {
        Long id = warehouses.findFirstByUserIdOrderByIdAsc(userId)
                .orElseGet(() -> warehouses.saveAndFlush(new Warehouse(userId, DEFAULT_WAREHOUSE_NAME)))
                .getId();
        return warehouses.findByIdForUpdate(id).orElseThrow();
    }
}
