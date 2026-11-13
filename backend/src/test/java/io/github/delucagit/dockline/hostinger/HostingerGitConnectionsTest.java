package io.github.delucagit.dockline.hostinger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Reading {@code owner/name} out of the clone addresses Hostinger's Git section returns. */
class HostingerGitConnectionsTest {

    @ParameterizedTest
    @CsvSource({
            "https://github.com/octocat/my-site.git, octocat/my-site",
            "https://github.com/octocat/my-site, octocat/my-site",
            "https://github.com/octocat/my-site/, octocat/my-site",
            "http://github.com/octocat/my-site.git, octocat/my-site",
            "https://www.github.com/octocat/my-site.git, octocat/my-site",
            "https://***@github.com/octocat/my-site.git, octocat/my-site",
            "https://***:***@github.com/octocat/my-site.git, octocat/my-site",
            "https://octocat:***@github.com/octocat/my-site.git, octocat/my-site",
            "git@github.com:octocat/my-site.git, octocat/my-site",
            "git@github.com:octocat/my-site, octocat/my-site",
            "git@github.com:123/456.git, 123/456",
            "ssh://git@github.com/octocat/my-site.git, octocat/my-site",
            "ssh://git@github.com:22/octocat/my-site.git, octocat/my-site",
            "ssh://git@ssh.github.com:443/octocat/my-site.git, octocat/my-site",
            "git://github.com/octocat/my-site.git, octocat/my-site",
            "GIT@GitHub.com:Octo-Org/My.Site_2.git, Octo-Org/My.Site_2",
            "'  git@github.com:octocat/my-site.git  ', octocat/my-site",
            "git@github.com:octocat/site.git.git, octocat/site.git",
    })
    void githubAddressesGiveTheFullName(String url, String fullName) {
        assertThat(HostingerGitConnections.githubFullName(url)).hasValue(fullName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://gitlab.com/octocat/my-site.git",
            "git@bitbucket.org:octocat/my-site.git",
            "https://github.com.evil.example/octocat/my-site.git",
            "https://notgithub.com/octocat/my-site.git",
            "https://example.com/github.com/octocat/my-site.git",
            "https://github.com/octocat",
            "https://github.com/octocat/my-site/tree/main",
            "git@github.com:octocat/..",
            "git@github.com:-bad/my-site.git",
            "octocat/my-site",
            "",
    })
    void otherAddressesAreNotGithubRepositories(String url) {
        assertThat(HostingerGitConnections.githubFullName(url)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void noAddressIsNotAGithubRepository() {
        assertThat(HostingerGitConnections.githubFullName(null)).isEmpty();
    }
}
