/*
 * The MIT License
 *
 * Copyright (c) 2011, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.reverse_proxy_auth;

import static hudson.Util.fixEmpty;
import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.fixNull;
import groovy.lang.Binding;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.security.GroupDetails;
import hudson.security.LDAPSecurityRealm;
import hudson.security.SecurityRealm;
import hudson.util.FormValidation;
import hudson.util.Scrambler;
import hudson.util.spring.BeanBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import jenkins.model.Jenkins;

import org.acegisecurity.Authentication;
import org.acegisecurity.AuthenticationManager;
import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.context.SecurityContextHolder;
import org.acegisecurity.ldap.InitialDirContextFactory;
import org.acegisecurity.ldap.LdapTemplate;
import org.acegisecurity.ldap.search.FilterBasedLdapUserSearch;
import org.acegisecurity.providers.UsernamePasswordAuthenticationToken;
import org.acegisecurity.userdetails.UserDetails;
import org.acegisecurity.userdetails.UserDetailsService;
import org.acegisecurity.userdetails.UsernameNotFoundException;
import org.acegisecurity.userdetails.ldap.LdapUserDetails;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.jenkinsci.plugins.reverse_proxy_auth.service.ProxyLDAPUserDetailsService;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.web.context.WebApplicationContext;

/**
 * @author Kohsuke Kawaguchi
 */
public class ReverseProxySecurityRealm extends SecurityRealm {

	private static final Logger LOGGER = Logger.getLogger(ReverseProxySecurityRealm.class.getName());

	/**
	 * LDAP filter to look for groups by their names.
	 *
	 * "{0}" is the group name as given by the user.
	 * See http://msdn.microsoft.com/en-us/library/aa746475(VS.85).aspx for the syntax by example.
	 * WANTED: The specification of the syntax.
	 */
	public static String GROUP_SEARCH = System.getProperty(LDAPSecurityRealm.class.getName()+".groupSearch",
			"(& (cn={0}) (| (objectclass=groupOfNames) (objectclass=groupOfUniqueNames) (objectclass=posixGroup)))");

	/**
	 * Scrambled password, used to first bind to LDAP.
	 */
	private final String managerPassword;

	/**
	 * Created in {@link #createSecurityComponents()}. Can be used to connect to LDAP.
	 */
	private transient LdapTemplate ldapTemplate;

	/**
	 * Keeps the state of connected users and their granted authorities.
	 */
	private final Hashtable<String, GrantedAuthority[]> authContext;

	/**
	 * LDAP server name(s) separated by spaces, optionally with TCP port number, like "ldap.acme.org"
	 * or "ldap.acme.org:389" and/or with protcol, like "ldap://ldap.acme.org".
	 */
	public final String server;

	/**
	 * The root DN to connect to. Normally something like "dc=sun,dc=com"
	 *
	 * How do I infer this?
	 */
	public final String rootDN;

	/**
	 * Allow the rootDN to be inferred? Default is false.
	 * If true, allow rootDN to be blank.
	 */
	public final boolean inhibitInferRootDN;

	/**
	 * Specifies the relative DN from {@link #rootDN the root DN}.
	 * This is used to narrow down the search space when doing user search.
	 *
	 * Something like "ou=people" but can be empty.
	 */
	public final String userSearchBase;

	/**
	 * Query to locate an entry that identifies the user, given the user name string.
	 *
	 * Normally "uid={0}"
	 *
	 * @see FilterBasedLdapUserSearch
	 */
	public final String userSearch;

	/**
	 * This defines the organizational unit that contains groups.
	 *
	 * Normally "" to indicate the full LDAP search, but can be often narrowed down to
	 * something like "ou=groups"
	 *
	 * @see FilterBasedLdapUserSearch
	 */
	public final String groupSearchBase;

	/**
	 * Query to locate an entry that identifies the group, given the group name string. If non-null it will override
	 * the default specified by {@link #GROUP_SEARCH}
	 *
	 * @since 1.5
	 */
	public final String groupSearchFilter;

	/**
	 * If non-null, we use this and {@link #managerPassword}
	 * when binding to LDAP.
	 *
	 * This is necessary when LDAP doesn't support anonymous access.
	 */
	public final String managerDN;

	/**
	 * The name of the header which the username has to be extracted from.
	 */
	public final String forwardedUser;

	/**
	 * The username retrieved from the header.
	 */
	public String retrievedUsername;

	/**
	 * The authorities that are granted to the authenticated user.
	 */
	public GrantedAuthority[] authorities;

	@DataBoundConstructor
	public ReverseProxySecurityRealm(String forwardedUser, String server, String rootDN, boolean inhibitInferRootDN, String userSearchBase,
			String userSearch, String groupSearchBase, String groupSearchFilter, String managerDN, String managerPassword) {

		this.forwardedUser = fixEmptyAndTrim(forwardedUser);
		//
		this.server = fixEmptyAndTrim(server);
		this.managerDN = fixEmpty(managerDN);
		this.managerPassword = Scrambler.scramble(fixEmpty(managerPassword));
		this.inhibitInferRootDN = inhibitInferRootDN;

		if (this.server != null) {
			if(!inhibitInferRootDN && fixEmptyAndTrim(rootDN) == null) rootDN = fixNull(inferRootDN(server));
			this.rootDN = rootDN.trim();
		} else {
			this.rootDN = null;
		}

		this.userSearchBase = fixNull(userSearchBase).trim();
		userSearch = fixEmptyAndTrim(userSearch);
		this.userSearch = userSearch != null ? userSearch : "uid={0}";
		this.groupSearchBase = fixEmptyAndTrim(groupSearchBase);
		this.groupSearchFilter = fixEmptyAndTrim(groupSearchFilter);

		authorities = new GrantedAuthority[0];
		authContext = new Hashtable<String, GrantedAuthority[]>();
	}

	/**
	 * Name of the HTTP header to look at.
	 */
	public String getForwardedUser() {
		return forwardedUser;
	}

	public String getServerUrl() {
		if (server == null) {
			return null;
		}
		StringBuilder buf = new StringBuilder();
		boolean first = true;

		for (String s: server.split("\\s+")) {
			if (s.trim().length() == 0) continue;
			if (first) first = false; else buf.append(' ');
			buf.append(addPrefix(s));
		}
		return buf.toString();
	}

	public String getGroupSearchFilter() {
		return groupSearchFilter;
	}

	/**
	 * Infer the root DN.
	 *
	 * @return null if not found.
	 */
	private String inferRootDN(String server) {
		try {
			Hashtable<String,String> props = new Hashtable<String,String>();
			if(managerDN != null) {
				props.put(Context.SECURITY_PRINCIPAL, managerDN);
				props.put(Context.SECURITY_CREDENTIALS, getManagerPassword());
			}
			props.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
			props.put(Context.PROVIDER_URL, toProviderUrl(fixNull(getServerUrl()), ""));

			DirContext ctx = new InitialDirContext(props);
			Attributes atts = ctx.getAttributes("");
			Attribute a = atts.get("defaultNamingContext");
			if(a != null && a.get() != null) { // this entry is available on Active Directory. See http://msdn2.microsoft.com/en-us/library/ms684291(VS.85).aspx
				return a.get().toString();
			}

			a = atts.get("namingcontexts");
			if(a == null) {
				LOGGER.warning("namingcontexts attribute not found in root DSE of " + server);
				return null;
			}
			return a.get().toString();
		} catch (NamingException e) {
			LOGGER.log(Level.WARNING,"Failed to connect to LDAP to infer Root DN for "+server,e);
			return null;
		}
	}

	public static String toProviderUrl(String serverUrl, String rootDN) {
		if (serverUrl == null) {
			return null;
		}
		StringBuilder buf = new StringBuilder();
		boolean first = true;
		for (String s: serverUrl.split("\\s+")) {
			if (s.trim().length() == 0) continue;
			if (first) first = false; else buf.append(' ');
			s = addPrefix(s);
			buf.append(s);
			if (!s.endsWith("/")) buf.append('/');
			buf.append(fixNull(rootDN));
		}
		return buf.toString();
	}

	public String getManagerPassword() {
		return Scrambler.descramble(managerPassword);
	}

	public String getLDAPURL() {
		return toProviderUrl(getServerUrl(), fixNull(rootDN));
	}

	@Override
	public Filter createFilter(FilterConfig filterConfig) {
		return new Filter() {
			public void init(FilterConfig filterConfig) throws ServletException {
			}

			public void doFilter(ServletRequest request,
					ServletResponse response, FilterChain chain)
							throws IOException, ServletException {
				HttpServletRequest r = (HttpServletRequest) request;

				retrievedUsername = r.getHeader(forwardedUser);

				Authentication auth = Hudson.ANONYMOUS;
				if (retrievedUsername != null) {
					//LOGGER.log(Level.INFO, "USER LOGGED IN: {0}", retrievedUsername);
					if (getLDAPURL() != null) {

						GrantedAuthority []  storedGrants = authContext.get(retrievedUsername);
						if (storedGrants != null && storedGrants.length > 1) {
							authorities = storedGrants;
						} else {
							LdapUserDetails userDetails = (LdapUserDetails) loadUserByUsername(retrievedUsername);
							authorities = userDetails.getAuthorities();

							Set<GrantedAuthority> tempLocalAuthorities = new HashSet<GrantedAuthority>(Arrays.asList(authorities));
							tempLocalAuthorities.add(AUTHENTICATED_AUTHORITY);

							authorities = tempLocalAuthorities.toArray(new GrantedAuthority[0]);
						}

					} else {
						Set<GrantedAuthority> tempLocalAuthorities = new HashSet<GrantedAuthority>();
						tempLocalAuthorities.add(AUTHENTICATED_AUTHORITY);

						authorities = tempLocalAuthorities.toArray(new GrantedAuthority[0]);
					}
					authContext.put(retrievedUsername, authorities);
					auth = new UsernamePasswordAuthenticationToken(retrievedUsername, "", authorities);

				}
				SecurityContextHolder.getContext().setAuthentication(auth);
				chain.doFilter(r, response);
			}

			public void destroy() {
			}
		};
	}

	@Override
	public boolean canLogOut() {
		return false;
	}

	@Override
	public SecurityComponents createSecurityComponents() {
		Binding binding = new Binding();
		binding.setVariable("instance", this);

		BeanBuilder builder = new BeanBuilder(Jenkins.getInstance().pluginManager.uberClassLoader);

		String fileName;
		if (getLDAPURL() != null) {
			fileName = "ReverseProxyLDAPSecurityRealm.groovy";
		} else {
			fileName = "ReverseProxySecurityRealm.groovy";
		}

		try {
			File override = new File(Jenkins.getInstance().getRootDir(), fileName);
			builder.parse(override.exists() ? new AutoCloseInputStream(new FileInputStream(override)) :
				getClass().getResourceAsStream(fileName), binding);
		} catch (FileNotFoundException e) {
			throw new Error("Failed to load "+fileName,e);
		}
		WebApplicationContext appContext = builder.createApplicationContext();

		if (getLDAPURL() != null) {
			ldapTemplate = new LdapTemplate(findBean(InitialDirContextFactory.class, appContext));
		}

		if (getLDAPURL() != null) {
			return new SecurityComponents(findBean(AuthenticationManager.class, appContext), new ProxyLDAPUserDetailsService(this, appContext));
		} else {
			return new SecurityComponents(new AuthenticationManager() {
				public Authentication authenticate(Authentication authentication) {
					return authentication;
				}
			}, new UserDetailsService() {
				public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException, DataAccessException {
					throw new UsernameNotFoundException(username);
				}
			});
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException, DataAccessException {
		return getSecurityComponents().userDetails.loadUserByUsername(username);
	}

	@Override
	@SuppressWarnings("unchecked")
	public GroupDetails loadGroupByGroupname(String groupname) throws UsernameNotFoundException, DataAccessException {

		// TODO: obtain a DN instead so that we can obtain multiple attributes later
		String searchBase = groupSearchBase != null ? groupSearchBase : "";
		String searchFilter = groupSearchFilter != null ? groupSearchFilter : GROUP_SEARCH;
		final Set<String> groups = ldapTemplate.searchForSingleAttributeValues(searchBase, searchFilter, new String[]{groupname}, "cn");

		if(groups.isEmpty())
			throw new UsernameNotFoundException(groupname);

		return new GroupDetails() {
			@Override
			public String getName() {
				return groups.iterator().next();
			}
		};
	}

	public <T> T extractBean(Class<T> type, WebApplicationContext appContext) {
		T returnedObj = findBean(type, appContext);
		return returnedObj;
	}

	@Extension
	public static class ProxyLDAPDescriptor extends Descriptor<SecurityRealm> {

		@Override
		public String getDisplayName() {
			return Messages.ReverseProxySecurityRealm_DisplayName();
		}

		public FormValidation doServerCheck(
				@QueryParameter final String server,
				@QueryParameter final String managerDN,
				@QueryParameter final String managerPassword) {

			if(!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER))
				return FormValidation.ok();

			try {
				Hashtable<String,String> props = new Hashtable<String,String>();
				if(managerDN!=null && managerDN.trim().length() > 0  && !"undefined".equals(managerDN)) {
					props.put(Context.SECURITY_PRINCIPAL,managerDN);
				}
				if(managerPassword!=null && managerPassword.trim().length() > 0 && !"undefined".equals(managerPassword)) {
					props.put(Context.SECURITY_CREDENTIALS,managerPassword);
				}

				props.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
				props.put(Context.PROVIDER_URL, toProviderUrl(server, ""));


				DirContext ctx = new InitialDirContext(props);
				ctx.getAttributes("");
				return FormValidation.ok();   // connected
			} catch (NamingException e) {
				// trouble-shoot
				Matcher m = Pattern.compile("(ldaps?://)?([^:]+)(?:\\:(\\d+))?(\\s+(ldaps?://)?([^:]+)(?:\\:(\\d+))?)*").matcher(server.trim());
				if(!m.matches())
					return FormValidation.error(hudson.security.Messages.LDAPSecurityRealm_SyntaxOfServerField());

				try {
					InetAddress adrs = InetAddress.getByName(m.group(2));
					int port = m.group(1)!=null ? 636 : 389;
					if(m.group(3)!=null)
						port = Integer.parseInt(m.group(3));
					Socket s = new Socket(adrs,port);
					s.close();
				} catch (UnknownHostException x) {
					return FormValidation.error(hudson.security.Messages.LDAPSecurityRealm_UnknownHost(x.getMessage()));
				} catch (IOException x) {
					return FormValidation.error(x,hudson.security.Messages.LDAPSecurityRealm_UnableToConnect(server, x.getMessage()));
				}

				// otherwise we don't know what caused it, so fall back to the general error report
				// getMessage() alone doesn't offer enough
				return FormValidation.error(e,hudson.security.Messages.LDAPSecurityRealm_UnableToConnect(server, e));
			} catch (NumberFormatException x) {
				// The getLdapCtxInstance method throws this if it fails to parse the port number
				return FormValidation.error(hudson.security.Messages.LDAPSecurityRealm_InvalidPortNumber());
			}
		}
	}

	/**
	 * If the given "server name" is just a host name (plus optional host name), add ldap:// prefix.
	 * Otherwise assume it already contains the scheme, and leave it intact.
	 */
	private static String addPrefix(String server) {
		if(server.contains("://"))  return server;
		else    return "ldap://"+server;
	}
}
