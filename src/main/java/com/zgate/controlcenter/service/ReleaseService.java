package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Release;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.CreateReleaseRequest;
import com.zgate.controlcenter.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReleaseService {

    private final ReleaseRepository repo;

    @Cacheable("releases")
    public List<Release> findAll() {
        return repo.findAll();
    }

    public Release findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ControlCenterException("Release not found: " + id));
    }

    public Release getLatestStable() {
        return repo.findByIsLatestTrue()
            .orElseGet(() -> repo.findByChannelOrderByPublishedAtDesc(Release.Channel.STABLE)
                .stream().findFirst()
                .orElseThrow(() -> new ControlCenterException("No stable release found")));
    }

    @CacheEvict(value = "releases", allEntries = true)
    @Transactional
    public Release publish(CreateReleaseRequest req, String publishedBy) {
        if (repo.findByVersion(req.getVersion()).isPresent()) {
            throw new ControlCenterException("Version already exists: " + req.getVersion());
        }
        // Clear existing latest flag for same channel
        if (req.isLatest()) {
            repo.findByIsLatestTrue().ifPresent(prev -> {
                prev.setLatest(false);
                repo.save(prev);
            });
        }
        return repo.save(Release.builder()
            .version(req.getVersion())
            .channel(req.getChannel())
            .dockerTag(req.getDockerTag())
            .dockerRegistry(req.getDockerRegistry())
            .imageDigest(blankToNull(req.getImageDigest()))
            .webImageDigest(blankToNull(req.getWebImageDigest()))
            .releaseNotes(req.getReleaseNotes())
            .hasBreakingChanges(req.isHasBreakingChanges())
            .migrations(req.getMigrations())
            .isLatest(req.isLatest())
            .publishedBy(publishedBy)
            .build());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @CacheEvict(value = "releases", allEntries = true)
    @Transactional
    public Release approve(UUID id) {
        Release release = findById(id);
        release.setApprovalStatus(Release.ApprovalStatus.APPROVED);
        return repo.save(release);
    }

    @CacheEvict(value = "releases", allEntries = true)
    @Transactional
    public Release reject(UUID id) {
        Release release = findById(id);
        release.setApprovalStatus(Release.ApprovalStatus.REJECTED);
        return repo.save(release);
    }
}
