/*
 * Demoiselle Framework
 * Copyright (C) 2010 SERPRO
 * ----------------------------------------------------------------------------
 * This file is part of Demoiselle Framework.
 * 
 * Demoiselle Framework is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License version 3
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License version 3
 * along with this program; if not,  see <http://www.gnu.org/licenses/>
 * or write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA  02110-1301, USA.
 * ----------------------------------------------------------------------------
 * Este arquivo é parte do Framework Demoiselle.
 * 
 * O Framework Demoiselle é um software livre; você pode redistribuí-lo e/ou
 * modificá-lo dentro dos termos da GNU LGPL versão 3 como publicada pela Fundação
 * do Software Livre (FSF).
 * 
 * Este programa é distribuído na esperança que possa ser útil, mas SEM NENHUMA
 * GARANTIA; sem uma garantia implícita de ADEQUAÇÃO a qualquer MERCADO ou
 * APLICAÇÃO EM PARTICULAR. Veja a Licença Pública Geral GNU/LGPL em português
 * para maiores detalhes.
 * 
 * Você deve ter recebido uma cópia da GNU LGPL versão 3, sob o título
 * "LICENCA.txt", junto com esse programa. Se não, acesse <http://www.gnu.org/licenses/>
 * ou escreva para a Fundação do Software Livre (FSF) Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02111-1301, USA.
 */
package br.gov.frameworkdemoiselle.internal.context;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.spi.Context;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeanManager;

import org.slf4j.Logger;

import br.gov.frameworkdemoiselle.DemoiselleException;
import br.gov.frameworkdemoiselle.annotation.StaticScoped;
import br.gov.frameworkdemoiselle.internal.producer.LoggerProducer;
import br.gov.frameworkdemoiselle.util.Beans;
import br.gov.frameworkdemoiselle.util.ResourceBundle;

/**
 * <p>
 * Manage custom contexts relevant to Demoiselle operations.
 * </p>
 * <p>
 * When starting, the ContextManager must be initialized by calling {@link #initialize(AfterBeanDiscovery event)} inside
 * any methods observing the {@link AfterBeanDiscovery} event. Upon initialization a {@link StaticContext} will be
 * created to handle {@link StaticScoped} beans (but not activated, you must call
 * {@link #activate(Class customContextClass, Class scope)} to activate this context).
 * </p>
 * <p>
 * If an extension wants to manage another custom context, it must first call
 * {@link #add(CustomContext context, AfterBeanDiscovery event)} to add it's context to the list of managed contexts and
 * then call {@link #activate(Class customContextClass, Class scope)} whenever it wants to activate this added context
 * (contexts added through the {@link #add(CustomContext context, AfterBeanDiscovery event)} method are also not
 * activated upon adding).
 * </p>
 * 
 * @author serpro
 */
public final class ContextManager {

	private static final Map<ClassLoader, List<CustomContextCounter>> contextsCache = Collections
			.synchronizedMap(new HashMap<ClassLoader, List<CustomContextCounter>>());

	private static final Map<ClassLoader, Boolean> initializedCache = Collections
			.synchronizedMap(new HashMap<ClassLoader, Boolean>());

	private ContextManager() {
	}

	private synchronized static List<CustomContextCounter> getContexts() {
		List<CustomContextCounter> contexts = contextsCache.get(getCurrentClassLoader());

		if (contexts == null) {
			contexts = Collections.synchronizedList(new ArrayList<CustomContextCounter>());
			contextsCache.put(getCurrentClassLoader(), contexts);
		}

		return contexts;
	}

	private synchronized static boolean isInitialized() {
		Boolean initialized = initializedCache.get(getCurrentClassLoader());
		
		if (initialized == null) {
			initialized = false;
			initializedCache.put(getCurrentClassLoader(), initialized);
		}
		
		return initialized;
	}

	private static void setInitialized(boolean initialized) {
		initializedCache.put(getCurrentClassLoader(), initialized);
	}

	private static ClassLoader getCurrentClassLoader() {
		return Thread.currentThread().getContextClassLoader();
	}

	/**
	 * <p>
	 * Initializes this manager and adds the {@link StaticContext} context to the list of managed contexts. Other
	 * contexts must be added before they can be activated.
	 * </p>
	 * <p>
	 * It's OK to call this method multiple times, it will be initialized only once.
	 * </p>
	 * 
	 * @param event
	 *            The CDI event indicating all beans have been discovered.
	 */
	public static void initialize(AfterBeanDiscovery event) {
		if (isInitialized()) {
			return;
		}

		add(new StaticContext(), event);
		setInitialized(true);
	}

	/**
	 * <p>
	 * Adds a context to the list of managed contexts.
	 * </p>
	 * <p>
	 * A context added through this method will be deactivated before management can start. Only after calling
	 * {@link #activate(Class customContextClass, Class scope)} the context will be activated.
	 * </p>
	 * <p>
	 * Trying to add a context already managed will result in this method call being ignored.
	 * </p>
	 * 
	 * @param context
	 *            The context to be added
	 * @param event
	 *            The CDI event indicating all beans have been discovered.
	 */
	public static void add(CustomContext context, AfterBeanDiscovery event) {
		for (CustomContextCounter contextCounter : getContexts()) {
			if (contextCounter.isSame(context.getClass(), context.getScope())) {

				ContextManager.getLogger().trace(
						ContextManager.getBundle().getString("bootstrap-context-already-managed",
								context.getClass().getCanonicalName(), context.getScope().getCanonicalName()));

				return;
			}
		}

		ContextManager.getLogger().trace(
				ContextManager.getBundle().getString("bootstrap-context-added", context.getClass().getCanonicalName(),
						context.getScope().getCanonicalName()));

		context.setActive(false);
		event.addContext(context);
		getContexts().add(new CustomContextCounter(context));
	}

	/**
	 * <p>
	 * Activates a managed context.
	 * </p>
	 * <p>
	 * To be activated, a context must fulfill the following requisites:
	 * <ul>
	 * <li>Must be managed by this class (be of type {@link StaticScoped} or be added with
	 * {@link #add(CustomContext context, AfterBeanDiscovery event)})</li>
	 * <li>Must be of a scope not already attached to another active context</li>
	 * </ul>
	 * </p>
	 * 
	 * @param customContextClass
	 *            Type of context to activate
	 * @param scope
	 *            The scope to activate this context for
	 * @return <code>true</code> if there is a managed context of the provided type and scope and no other context is
	 *         active for the provided scope, <code>false</code> if there is a managed context of the provided type and
	 *         scope but another context is active for the provided scope.
	 * @throws DemoiselleException
	 *             if there is no managed context of the provided type and scope.
	 */
	public static synchronized void activate(Class<? extends CustomContext> customContextClass,
			Class<? extends Annotation> scope) {
		if (!isInitialized()) {
			throw new DemoiselleException(getBundle().getString("custom-context-manager-not-initialized"));
		}

		for (CustomContextCounter context : getContexts()) {
			if (context.isSame(customContextClass, scope)) {
				context.activate();
				return;
			}
		}

		throw new DemoiselleException(getBundle().getString("custom-context-not-found",
				customContextClass.getCanonicalName(), scope.getSimpleName()));
	}

	/**
	 * <p>
	 * Deactivates a managed context.
	 * </p>
	 * <p>
	 * To be deactivated, a context must fulfill the following requisites:
	 * <ul>
	 * <li>Must be managed by this class (be of type {@link StaticScoped} or be added with
	 * {@link #add(CustomContext context, AfterBeanDiscovery event)})</li>
	 * <li>Must have been activated by a previous call to {@link #activate(Class customContextClass, Class scope)}</li>
	 * <li>This previous call must have returned <code>true</code>.
	 * </ul>
	 * </p>
	 * 
	 * @param customContextClass
	 *            Type of context to deactivate
	 * @param scope
	 *            The scope the context controled when it was active
	 * @return <code>true</code> if there was an active context of this type and scope and it was activated by a
	 *         previous call to {@link #activate(Class customContextClass, Class scope)}
	 * @throws DemoiselleException
	 *             if there is no managed context of the provided type and scope.
	 */
	public static synchronized void deactivate(Class<? extends CustomContext> customContextClass,
			Class<? extends Annotation> scope) {
		if (!isInitialized()) {
			throw new DemoiselleException(getBundle().getString("custom-context-manager-not-initialized"));
		}

		for (CustomContextCounter context : getContexts()) {
			if (context.isSame(customContextClass, scope)) {
				context.deactivate();
				return;
			}
		}

		throw new DemoiselleException(getBundle().getString("custom-context-not-found",
				customContextClass.getCanonicalName(), scope.getSimpleName()));
	}

	/**
	 * <p>
	 * This method should be called when the application is shutting down.
	 * </p>
	 */
	public static synchronized void shutdown() {
		for (CustomContextCounter context : getContexts()) {
			context.shutdown();
		}

		getContexts().clear();
		setInitialized(false);
	}

	static Logger getLogger() {
		return LoggerProducer.create(ContextManager.class);
	}

	static ResourceBundle getBundle() {
		return new ResourceBundle("demoiselle-core-bundle", Locale.getDefault());
	}
}

/**
 * Class that counts how many attemps to activate and deactivate this context received, avoiding cases where one client
 * activates given context and another one deactivates it, leaving the first client with no active context before
 * completion.
 * 
 * @author serpro
 */
class CustomContextCounter {

	private CustomContext context;

	private int activationCounter = 0;

	public CustomContextCounter(CustomContext customContext) {
		this.context = customContext;
	}

	public boolean isSame(Class<? extends CustomContext> customContextClass, Class<? extends Annotation> scope) {
		if (context.getClass().getCanonicalName().equals(customContextClass.getCanonicalName())
				&& context.getScope().equals(scope)) {
			return true;
		}

		return false;
	}

	public CustomContext getInternalContext() {
		return this.context;
	}

	public void setInternalContext(CustomContext context) {
		this.context = context;
	}

	public synchronized void activate() {
		try {
			BeanManager beanManager = Beans.getReference(BeanManager.class);
			Context c = beanManager.getContext(context.getScope());

			if (c == context) {
				activationCounter++;
			} else {
				ContextManager.getLogger().trace(
						ContextManager.getBundle().getString("custom-context-already-activated",
								context.getClass().getCanonicalName(), c.getScope().getCanonicalName(),
								c.getClass().getCanonicalName()));
			}
		} catch (ContextNotActiveException ce) {
			context.setActive(true);
			activationCounter++;
			ContextManager.getLogger().trace(
					ContextManager.getBundle().getString("custom-context-was-activated",
							context.getClass().getCanonicalName(), context.getScope().getCanonicalName()));
		}
	}

	public synchronized void deactivate() {
		try {
			Context c = Beans.getBeanManager().getContext(context.getScope());
			if (c == context) {
				activationCounter--;
				if (activationCounter == 0) {
					context.setActive(false);
					ContextManager.getLogger().trace(
							ContextManager.getBundle().getString("custom-context-was-deactivated",
									context.getClass().getCanonicalName(), context.getScope().getCanonicalName()));
				}
			}
		} catch (ContextNotActiveException ce) {
		}
	}

	public synchronized void shutdown() {
		context.setActive(false);
		context = null;
		activationCounter = 0;
	}
}
