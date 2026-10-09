package com.client.ldap.identity.sync.internal;

import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.model.role.RoleConstants;
import com.liferay.portal.kernel.service.RoleLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceWrapper;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.kernel.service.UserLocalServiceWrapper;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.Validator;

import java.util.Locale;
import java.util.Map;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

/**
 * Resuelve, en la sincronizacion LDAP -> Liferay, el alta que fallaria con
 * {@code UserEmailAddressException.MustNotBeDuplicate}: el email del usuario
 * de Active Directory ya lo tiene OTRO usuario de Liferay (otro screenName).
 * Casos tipicos: externo que pasa a interno (nuevo numero de expediente, mismo
 * email) y email reciclado para otra persona.
 *
 * <p>
 * Politica acordada con el cliente: borrar el usuario en conflicto y crear el
 * nuevo.
 * </p>
 *
 * <p>
 * <b>Comprobacion previa, no captura de la excepcion.</b> Antes de llamar a
 * {@code super.addUser} se busca si ya existe un usuario con ese email. Si
 * existe, se borra y despues se hace el alta. Asi nunca salta la excepcion, y
 * no hay ninguna transaccion marcada como fallida que pueda impedir el
 * reintento. Se sabe que el usuario encontrado es otro (distinto screenName)
 * porque el importador LDAP solo llama a {@code addUser} cuando no ha
 * encontrado a nadie con el screenName entrante.
 * </p>
 *
 * <p>
 * Se engancha en {@code UserLocalService} (y no en {@code LDAPUserImporter} ni
 * en un {@code Authenticator}) porque es el unico punto por el que pasan TODOS
 * los flujos: login con formulario, import programado, import al arrancar y
 * SAML con import LDAP. Solo se sobrescribe {@code addUser}; el resto de
 * metodos (incluidos {@code updateUser} y {@code deleteUser}) se delegan sin
 * ningun cambio.
 * </p>
 *
 * <p>
 * SEGURIDAD: {@code addUser} tambien lo llaman el alta de cuenta en el portal,
 * la administracion de usuarios y la API. La comprobacion solo se hace si la
 * llamada procede del import LDAP, lo cual se reconoce porque
 * {@code LDAPUserImporterImpl} marca su {@link ServiceContext} con el atributo
 * {@value #_ATTRIBUTE_LDAP_SERVER_ID} antes de llamar a {@code addUser}. En
 * cualquier otra llamada el coste es leer un atributo en memoria.
 * </p>
 *
 * IMPORTANTE -- punto de partida, no un artefacto listo para produccion sin
 * revision ni pruebas. Ver README.md.
 */
@Component(
	configurationPid = "com.client.ldap.identity.sync.LDAPUserIdentitySyncWrapper",
	configurationPolicy = ConfigurationPolicy.OPTIONAL, immediate = true,
	property = {}, service = ServiceWrapper.class
)
public class LDAPUserIdentitySyncWrapper extends UserLocalServiceWrapper {

	public LDAPUserIdentitySyncWrapper() {
		super(null);
	}

	/**
	 * Es este metodo, no el constructor, el que realmente engancha el wrapper
	 * al {@link UserLocalService} real. Sin el (y sin registrar el componente
	 * como {@code service = ServiceWrapper.class}), Liferay despliega y activa
	 * el componente sin errores, pero nunca lo encadena delante del servicio
	 * real: se queda inerte, sin interceptar nada y sin ningun error que lo
	 * delate.
	 */
	@Reference(unbind = "-")
	protected void setUserLocalService(UserLocalService userLocalService) {
		setWrappedService(userLocalService);
	}

	/**
	 * Si la llamada procede del import LDAP y el email ya lo tiene otro
	 * usuario, lo borra antes de crear el nuevo.
	 */
	@Override
	public User addUser(
			long creatorUserId, long companyId, boolean autoPassword,
			String password1, String password2, boolean autoScreenName,
			String screenName, String emailAddress, Locale locale,
			String firstName, String middleName, String lastName,
			long prefixListTypeId, long suffixListTypeId, boolean male,
			int birthdayMonth, int birthdayDay, int birthdayYear,
			String jobTitle, int type, long[] groupIds, long[] organizationIds,
			long[] roleIds, long[] userGroupIds, boolean sendEmail,
			ServiceContext serviceContext)
		throws PortalException {

		if (_originatesFromLdapImport(serviceContext)) {
			_deleteConflictingUser(companyId, screenName, emailAddress);
		}

		return super.addUser(
			creatorUserId, companyId, autoPassword, password1, password2,
			autoScreenName, screenName, emailAddress, locale, firstName,
			middleName, lastName, prefixListTypeId, suffixListTypeId, male,
			birthdayMonth, birthdayDay, birthdayYear, jobTitle, type, groupIds,
			organizationIds, roleIds, userGroupIds, sendEmail, serviceContext);
	}

	@Activate
	@Modified
	protected void activate(Map<String, Object> properties) {
		_protectAdministrators = GetterUtil.getBoolean(
			properties.get("protectAdministrators"), true);

		if (_log.isInfoEnabled()) {
			_log.info(
				"LDAPUserIdentitySyncWrapper activo: protectAdministrators=" +
					_protectAdministrators);
		}
	}

	/**
	 * Borra al usuario que tiene ese email, salvo que no exista, sea el guest
	 * o (por defecto) administrador. Si algo falla, no se propaga nada: el
	 * {@code super.addUser} que sigue lanzara la excepcion de siempre y el
	 * importador la tratara como hasta ahora.
	 */
	private void _deleteConflictingUser(
		long companyId, String newScreenName, String emailAddress) {

		if (Validator.isNull(emailAddress)) {
			return;
		}

		try {
			User conflictingUser = fetchUserByEmailAddress(
				companyId, emailAddress);

			if (conflictingUser == null) {
				return;
			}

			// Defensivo: si fuese el mismo screenName no es un conflicto (el
			// importador lo habria encontrado y no estariamos en addUser).

			if (Validator.isNotNull(newScreenName) &&
				newScreenName.equalsIgnoreCase(
					conflictingUser.getScreenName())) {

				return;
			}

			if (conflictingUser.isGuestUser()) {
				_log.warn(
					"El usuario en conflicto " + conflictingUser.getUserId() +
						" es el usuario guest; no se borra");

				return;
			}

			if (_protectAdministrators &&
				_roleLocalService.hasUserRole(
					conflictingUser.getUserId(), companyId,
					RoleConstants.ADMINISTRATOR, true)) {

				_log.warn(
					"El usuario en conflicto " + conflictingUser.getUserId() +
						" (" + conflictingUser.getScreenName() +
							") es administrador; no se borra");

				return;
			}

			_log.warn(
				String.format(
					"Email %s ya usado por el usuario userId=%s " +
						"screenName=%s (companyId=%s); se borra para crear " +
							"el nuevo con screenName=%s",
					emailAddress, conflictingUser.getUserId(),
					conflictingUser.getScreenName(), companyId, newScreenName));

			// ---------------------------------------------------------------
			// ALTERNATIVA (no activa): conservar el userId en vez de borrar.
			// ---------------------------------------------------------------
			//
			// En lugar de borrar al usuario en conflicto, se le cambia el
			// screenName por el nuevo (el parametro "screenName" de addUser,
			// que es el que llega del LDAP) y addUser DEVUELVE ese usuario en
			// lugar de crear otro. El importador LDAP hace a continuacion su
			// updateUser sobre el usuario devuelto, igual que si lo hubiera
			// creado. Se conservan el userId, los roles asignados a mano, el
			// contenido y las preferencias. Solo tiene sentido si es la misma
			// persona (externo -> interno); en un email reciclado para otra
			// persona heredaria lo de la anterior.
			//
			// Cambios necesarios (UserLocalService.updateScreenName(long,
			// String) existe con esa firma; sin compilar):
			//
			// 1. _deleteConflictingUser pasa a devolver el User (o null), y
			//    addUser devuelve ese usuario si no es null, en vez de llamar
			//    a super.addUser.
			//
			// 2. Sustituir la llamada a deleteUser por:
			//
			//    if (Validator.isNull(newScreenName) ||
			//        (fetchUserByScreenName(companyId, newScreenName) != null)) {
			//
			//        // vacio o ya en uso por otro usuario: no tocar nada
			//
			//        return null;
			//    }
			//
			//    return updateScreenName(
			//        conflictingUser.getUserId(), newScreenName);
			//
			// Cuidado: si el alta usa autoScreenName, "screenName" puede venir
			// vacio y no hay nada que renombrar. Y fetchUserByScreenName y
			// updateScreenName normalizan mayusculas; conviene probarlo con un
			// expediente real.

			deleteUser(conflictingUser);
		}
		catch (Exception exception) {
			_log.error(
				"No se pudo resolver el conflicto de email " + emailAddress,
				exception);
		}
	}

	private boolean _originatesFromLdapImport(ServiceContext serviceContext) {
		if (serviceContext == null) {
			return false;
		}

		return serviceContext.getAttribute(_ATTRIBUTE_LDAP_SERVER_ID) != null;
	}

	private static final String _ATTRIBUTE_LDAP_SERVER_ID = "ldapServerId";

	private static final Log _log = LogFactoryUtil.getLog(
		LDAPUserIdentitySyncWrapper.class);

	private volatile boolean _protectAdministrators = true;

	@Reference
	private RoleLocalService _roleLocalService;

}
