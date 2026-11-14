package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.hostinger.HostingerApi.Site;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.Commit;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.DeployOrigin;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.DeployStatus;
import java.time.Instant;
import java.util.Optional;

/**
 * Somewhere the latest deploy of a site can be read from. Today there is one: Hostinger's build history of
 * Node.js websites ({@link HostingerBuilds}). {@link SiteDeploys} only talks to this interface, so another source
 * can be added without changing the endpoint.
 */
interface DeploySource {

    /** How to get the source's answers. */
    enum Mode {
        /** Reuse saved answers; ask the source for the rest. */
        NORMAL,
        /** Ask the source again, skipping saved answers. */
        REFRESH,
        /** Only saved answers; the source is never asked. */
        SAVED_ONLY
    }

    /**
     * @param at when the deploy last changed state; null when not known
     * @param commit the deployed commit; null when not known
     * @param fetchedAt when the source answered; null when it was not asked (see {@link #none})
     */
    record Deploy(DeployStatus status, Instant at, Commit commit, DeployOrigin origin, Instant fetchedAt) {

        /** This source has no deploy information for the site, and did not need to ask to know that. */
        static Deploy none() {
            return new Deploy(DeployStatus.NONE, null, null, DeployOrigin.NONE, null);
        }
    }

    /**
     * The site's latest deploy. Empty only with {@link Mode#SAVED_ONLY}, when no answer is saved. Throws
     * {@link ApiProblem} (with the codes of the source's other endpoints) when the source cannot be asked.
     */
    Optional<Deploy> latest(Site site, Mode mode);
}
