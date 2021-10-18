package cz.mendelu.xotradov;

import hudson.security.Permission;
import jenkins.model.Jenkins;

/**
 * Sums up the permissions needed to work with the plugin.
 */
class PermissionHandler {
    static final Permission SIMPLE_QUEUE_MOVE_PERMISSION = Jenkins.MANAGE;
}
