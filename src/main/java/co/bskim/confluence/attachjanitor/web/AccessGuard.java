package co.bskim.confluence.attachjanitor.web;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Who may use this app: Confluence administrators, nobody else.
 *
 * <p>The report names every space in the instance and how much each one holds, which is
 * information a space administrator is not otherwise given about spaces they cannot see. There
 * is no per-space view in v1, so there is no weaker role to hand out.</p>
 */
@Named
public class AccessGuard
{
    private final UserManager userManager;

    @Inject
    public AccessGuard(@ComponentImport UserManager userManager)
    {
        this.userManager = userManager;
    }

    public boolean isLoggedIn()
    {
        return userManager.getRemoteUser() != null;
    }

    public boolean isAdmin()
    {
        UserProfile user = userManager.getRemoteUser();
        return user != null
                && (userManager.isAdmin(user.getUserKey())
                    || userManager.isSystemAdmin(user.getUserKey()));
    }

    public String currentUserName()
    {
        UserProfile user = userManager.getRemoteUser();
        return user == null ? "" : user.getUsername();
    }
}
