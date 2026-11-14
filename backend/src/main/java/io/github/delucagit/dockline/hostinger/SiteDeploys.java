package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.hostinger.DeploySource.Deploy;
import io.github.delucagit.dockline.hostinger.DeploySource.Mode;
import io.github.delucagit.dockline.hostinger.HostingerApi.Site;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.DeployOrigin;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.DeployStatus;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.SiteDeploy;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.SourceProblem;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The latest deploy of every site, for the Sites list. The websites come from {@link HostingerSiteList} (its
 * normal saved copy; its failures fail the whole call), the deploys from a {@link DeploySource}. A site is
 * identified by its domain (case does not matter).
 *
 * <p>One site's failure does not break the others: that site gets status UNKNOWN and a {@link SourceProblem}.
 * After a failure that concerns every site (rate limit, rejected token, token removed) the source is not asked
 * again in this call: the sites left over are answered from saved answers, or get the same problem.
 */
@Component
class SiteDeploys {

    private final HostingerSiteList siteList;
    private final DeploySource source;
    private final Clock clock;

    SiteDeploys(HostingerSiteList siteList, DeploySource source, Clock clock) {
        this.siteList = siteList;
        this.source = source;
        this.clock = clock;
    }

    /** {@code refresh} asks the deploy source again about every site; the website list keeps its saved copy. */
    SiteDeployApi.SiteDeploys list(boolean refresh) {
        List<Site> sites = siteList.list(false).sites();
        Mode mode = refresh ? Mode.REFRESH : Mode.NORMAL;
        // Set by a failure that would repeat for every site; from then on only saved answers are used.
        ApiProblem stopped = null;
        Instant fetchedAt = null;
        // By domain: Hostinger can list a domain twice.
        Map<String, SiteDeploy> deploys = new LinkedHashMap<>();
        for (Site site : sites) {
            if (site.domain() == null || deploys.containsKey(normalize(site.domain()))) {
                continue;
            }
            String domain = normalize(site.domain());
            Optional<Deploy> found = Optional.empty();
            ApiProblem problem = stopped;
            try {
                found = source.latest(site, stopped == null ? mode : Mode.SAVED_ONLY);
            } catch (ApiProblem e) {
                problem = e;
                if (!HostingerSiteList.UNAVAILABLE.equals(e.code())) {
                    stopped = e;
                }
            }
            if (found.isPresent()) {
                Deploy deploy = found.get();
                if (deploy.fetchedAt() != null && (fetchedAt == null || deploy.fetchedAt().isBefore(fetchedAt))) {
                    fetchedAt = deploy.fetchedAt();
                }
                deploys.put(domain, new SiteDeploy(domain, deploy.status(), deploy.at(), deploy.commit(),
                        deploy.origin(), List.of()));
            } else {
                deploys.put(domain, new SiteDeploy(domain, DeployStatus.UNKNOWN, null, null, DeployOrigin.HOSTINGER,
                        List.of(SourceProblem.hostinger(problem))));
            }
        }
        return new SiteDeployApi.SiteDeploys(List.copyOf(deploys.values()),
                fetchedAt != null ? fetchedAt : clock.instant());
    }

    private static String normalize(String domain) {
        return domain.strip().toLowerCase(Locale.ROOT);
    }
}
