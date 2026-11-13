package io.github.delucagit.dockline.hostinger;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** The response shapes of the Hostinger data endpoints. */
final class HostingerApi {

    private HostingerApi() {}

    @Schema(name = "HostingerSite", description = "One website in the user's Hostinger hosting (Hostinger's website list)")
    record Site(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "example.com",
                    description = "The website's domain; null for a website with no domain attached") String domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "u123456789",
                    description = "The hosting account's username (CloudLinux websites); null for other platforms")
            String username,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "12345",
                    description = "The hosting order (plan) the website belongs to") Long orderId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "wordpress",
                    description = "What Hostinger detected on the site: wordpress, builder, horizons, nodejs or other")
            String websiteType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    example = "/home/u123456789/domains/example.com/public_html",
                    description = "Root directory on the server (CloudLinux websites); null for other platforms")
            String rootDirectory,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "False when Hostinger has suspended the website") boolean enabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2024-01-15T10:30:00Z",
                    description = "When the website was created (ISO-8601, UTC)") Instant createdAt) {}

    @Schema(name = "HostingerSites", description = "The user's Hostinger websites, as Hostinger listed them at fetchedAt")
    record Sites(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Sorted by domain (A-Z); websites without a domain last") List<Site> sites,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-11-13T09:00:00Z",
                    description = "When Dockline fetched this from Hostinger (ISO-8601, UTC); up to 60 s old when "
                            + "the saved copy was reused") Instant fetchedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "True when the list stopped at "
                    + HostingerSiteList.MAX_PAGES + " pages of " + HostingerClient.WEBSITES_PER_PAGE
                    + " and more websites exist") boolean truncated) {}
}
