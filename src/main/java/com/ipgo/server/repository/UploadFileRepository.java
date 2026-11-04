package com.ipgo.server.repository;

import com.ipgo.server.entity.UploadFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface UploadFileRepository extends JpaRepository<UploadFile, Long> {
    @Query("SELECT u.warehouseId FROM UploadFile u WHERE u.id = :id")
    Optional<Long> warehouseId(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UploadFile u WHERE u.id = :id")
    Optional<UploadFile> findByIdForUpdate(@Param("id") long id);

    // 실패하지 않은 같은 기간 업로드 중 가장 최근 것. 있으면 "이미 올린 기간"이다.
    Optional<UploadFile> findFirstByWarehouseIdAndPeriodStartAndStatusNotOrderByRevisionNoDesc(
            Long warehouseId, LocalDate periodStart, String status);

    // 실패하지 않은 업로드 중 내용 해시가 같은 가장 최근 것. 있으면 "이미 올린 파일"이다.
    Optional<UploadFile> findFirstByWarehouseIdAndFileHashAndStatusNotOrderByIdDesc(
            Long warehouseId, String fileHash, String status);

    @Query("SELECT COALESCE(MAX(u.revisionNo), 0) FROM UploadFile u "
            + "WHERE u.warehouseId = :warehouseId AND u.periodStart = :periodStart")
    int lastRevision(@Param("warehouseId") Long warehouseId, @Param("periodStart") LocalDate periodStart);

    // 기간마다 가장 마지막 revision만 고른다.
    @Query("""
            SELECT u FROM UploadFile u
            WHERE u.warehouseId = :warehouseId
              AND u.revisionNo = (SELECT MAX(v.revisionNo) FROM UploadFile v
                                  WHERE v.warehouseId = u.warehouseId AND v.periodStart = u.periodStart)
            ORDER BY u.periodStart
            """)
    List<UploadFile> findLatestPerPeriod(@Param("warehouseId") Long warehouseId);

    Optional<UploadFile> findFirstByWarehouseIdOrderByIdDesc(Long warehouseId);
}
