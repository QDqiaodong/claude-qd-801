package com.concrete.plant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.concrete.plant.domain.MixerFaultRecord;

public interface MixerFaultRecordRepository extends JpaRepository<MixerFaultRecord, Long> {

    @Query("select coalesce(max(r.id), 0) from MixerFaultRecord r")
    long maxId();
}
