/**
 * The host contract shared by every engine in a host. JDK types and JSpecify annotations only (ADR-0016).
 *
 * <ul>
 *   <li>{@link com.horus.bpm.host.spi.ModuleDescriptor}: each engine starter contributes one; hosts do not
 *       implement it.
 *   <li>{@link com.horus.bpm.host.spi.EngineAccess}: who is calling, in which tenant, and whether they hold
 *       a permission. Engine controllers and {@code GET /api/bpm/me} both call it. A default is registered
 *       unless the host declares its own bean.
 *   <li>{@link com.horus.bpm.host.spi.TenantResolver} and {@link com.horus.bpm.host.spi.CallerContext}: a
 *       host implements the resolver when its principal carries no tenant attribute.
 * </ul>
 */
package com.horus.bpm.host.spi;
