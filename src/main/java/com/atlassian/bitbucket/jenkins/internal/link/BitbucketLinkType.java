package com.atlassian.bitbucket.jenkins.internal.link;

public enum BitbucketLinkType {

    BRANCH("View Branch"),
    REPO("Browse Repo");

    private final String displayName;

    BitbucketLinkType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
