package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.Release;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseRepository extends JpaRepository<Release, UUID> {
    Optional<Release> findByVersion(String version);
    Optional<Release> findByIsLatestTrue();
    List<Release> findByChannel(Release.Channel channel);
    List<Release> findByChannelOrderByPublishedAtDesc(Release.Channel channel);
}
