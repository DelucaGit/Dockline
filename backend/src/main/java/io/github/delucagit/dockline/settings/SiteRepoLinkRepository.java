package io.github.delucagit.dockline.settings;

import java.util.Map;
import java.util.Optional;

/**
 * Manual links between a site and its GitHub repository: at most one repository per site. Links survive an
 * app restart. The app keeps them in the JSON settings file ({@link JsonFileSiteRepoLinkRepository}); the
 * rest of the code only knows this interface, so a database could replace the file later.
 *
 * <p>A site is identified by its domain, e.g. {@code example.com}; upper/lower case and spaces around it
 * do not matter. A repository is its GitHub full name, {@code owner/name}.
 *
 * <p>Safe to use from several threads. Methods that change something throw
 * {@link SettingsUnavailableException} when the change could not be saved (nothing is changed then). Every
 * method throws {@link IllegalArgumentException} for a blank domain, and {@code save} also for a repository
 * that is not {@code owner/name}.
 */
public interface SiteRepoLinkRepository {

    /** The repository linked to this site, or empty when the site has no manual link. */
    Optional<String> find(String domain);

    /** Every manual link: domain (lower case) to repository full name, sorted by domain. A read-only copy. */
    Map<String, String> findAll();

    /** Links the site to this repository, replacing any earlier link of the site. */
    void save(String domain, String repoFullName);

    /** Removes the site's link. Returns false, and does nothing, when the site had none. */
    boolean remove(String domain);
}
