package com.eric.governanceApi.governanceApi.repository;

import java.time.Instant;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.eric.governanceApi.governanceApi.model.entity.DeviceGroupMembership;
import com.eric.governanceApi.governanceApi.model.entity.UserGroupAssignment;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

// Reusable filters for the searchable lists (events, errors, audit)
public final class SearchSpecs {

    private SearchSpecs() {}

    public static <T> Specification<T> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    public static <T> Specification<T> between(String attr, Instant from, Instant to) {
        return (root, query, cb) -> {
            if (from != null && to != null) return cb.between(root.get(attr), from, to);
            if (from != null) return cb.greaterThanOrEqualTo(root.get(attr), from);
            if (to != null) return cb.lessThanOrEqualTo(root.get(attr), to);
            return cb.conjunction();
        };
    }

    public static <T> Specification<T> equal(String attr, Object value) {
        return (root, query, cb) -> value == null ? cb.conjunction() : cb.equal(root.get(attr), value);
    }

    // Case-insensitive "contains" on any of the given paths (dot notation, e.g. "device.name").
    // Joins are LEFT so rows without a device still match on their own fields.
    public static <T> Specification<T> textSearch(String q, String... paths) {
        return (root, query, cb) -> {
            if (q == null || q.isBlank()) return cb.conjunction();
            String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            Predicate[] ors = new Predicate[paths.length];
            for (int i = 0; i < paths.length; i++) {
                String[] parts = paths[i].split("\\.");
                Path<?> path = root;
                From<?, ?> from = root;
                for (int j = 0; j < parts.length - 1; j++) {
                    from = from.join(parts[j], JoinType.LEFT);
                    path = from;
                }
                path = path.get(parts[parts.length - 1]);
                ors[i] = cb.like(cb.lower(path.as(String.class)), like);
            }
            return cb.or(ors);
        };
    }

    // Same scope as the existing findAllByKeycloakUserId queries: devices in any group of the user
    public static <T> Specification<T> deviceInUserGroups(String keycloakUserId) {
        return (root, query, cb) -> {
            Subquery<Long> groups = query.subquery(Long.class);
            Root<UserGroupAssignment> a = groups.from(UserGroupAssignment.class);
            groups.select(a.get("id").get("groupId"))
                  .where(cb.equal(a.get("id").get("keycloakUserId"), keycloakUserId));

            Subquery<Long> devices = query.subquery(Long.class);
            Root<DeviceGroupMembership> m = devices.from(DeviceGroupMembership.class);
            devices.select(m.get("id").get("deviceId"))
                   .where(m.get("id").get("groupId").in(groups));

            return root.get("device").get("id").in(devices);
        };
    }
}
