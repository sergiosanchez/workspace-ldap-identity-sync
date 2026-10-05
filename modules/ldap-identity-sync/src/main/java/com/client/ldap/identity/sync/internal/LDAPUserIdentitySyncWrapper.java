package com.client.ldap.identity.sync.internal;

import com.liferay.expando.kernel.model.ExpandoBridge;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.UserEmailAddressException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.model.UserGroupRole;
import com.liferay.portal.kernel.model.role.RoleConstants;
import com.liferay.portal.kernel.service.RoleLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceWrapper;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.kernel.service.UserLocalServiceWrapper;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.Validator;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

/**
 * Resuelve, en la sincronizacion LDAP -> Liferay, dos situaciones en que el
 * email de un usuario de Active Directory colisiona con un usuario que ya
 * existe en Liferay.
 *
 * <ol>
 *   <li><b>Alta que falla por email duplicado</b> (externo pasa a interno, o
 *       email reciclado para otra persona con OTRO screenName). El import
 *       busca por screenName, no encuentra al usuario e intenta CREAR uno; la
 *       creacion falla con {@link UserEmailAddressException.MustNotBeDuplicate}.
 *       Politica acordada con el cliente: {@link #addUser borrar el usuario en
 *       conflicto y repetir el alta una vez}.</li>
 *   <li><b>Sobrescritura silenciosa</b> (email Y screenName reciclados para
 *       otra persona). El import encuentra al usuario antiguo por screenName y
 *       lo actualiza con datos de otra persona, sin excepcion. Se detecta
 *       comparando el {@code objectGUID} de Active Directory guardado en el
 *       campo Expando {@value #EXPANDO_COLUMN_AD_OBJECT_GUID} con el que llega
 *       ahora; si difiere, {@link #updateUser la actualizacion se bloquea} y se
 *       deja un WARN para revision manual.</li>
 * </ol>
 *
 * <p>
 * El {@code objectGUID} llega desde {@link ADObjectGuidAttributesTransformer}
 * (convierte el valor binario de AD a texto) a traves de
 * {@link AdObjectGuidThreadLocal}. Para que el import LDAP pida ese atributo a
 * AD tiene que estar mapeado en la configuracion LDAP; ver README, "Mapeos y
 * configuracion necesarios".
 * </p>
 *
 * <p>
 * Se engancha en {@code UserLocalService} (y no en {@code LDAPUserImporter} ni
 * en un {@code Authenticator}) porque es el unico punto por el que pasan TODOS
 * los flujos: login con formulario, import programado, import al arrancar y
 * SAML con import LDAP.
 * </p>
 *
 * <p>
 * SEGURIDAD: {@code addUser} tambien lo llaman el alta de cuenta en el portal,
 * la administracion de usuarios y la API. El borrado de un usuario solo se
 * hace si la llamada procede del import LDAP, lo cual se reconoce porque
 * {@code LDAPUserImporterImpl} marca su {@link ServiceContext} con el atributo
 * {@value #_ATTRIBUTE_LDAP_SERVER_ID} antes de llamar a {@code addUser}.
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

	/**
	 * Nombre del campo Expando (custom field) del User donde se guarda el
	 * {@code objectGUID} de Active Directory. Hay que crearlo antes de
	 * desplegar este componente (Instance Settings > Custom Fields > User);
	 * ver README. Si no existe, {@link #_seedAdObjectGuid} avisa en el log y
	 * no guarda nada.
	 */
	public static final String EXPANDO_COLUMN_AD_OBJECT_GUID = "adObjectGUID";

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
	 * Si el alta falla por email duplicado y la llamada procede del import
	 * LDAP, borra el usuario en conflicto y repite el alta una unica vez.
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

		// El objectGUID de esta misma entrada LDAP lo dejo
		// ADObjectGuidAttributesTransformer en el ThreadLocal justo antes de
		// que el import llegue hasta aqui -- ver el javadoc de
		// AdObjectGuidThreadLocal.
		String incomingAdObjectGuid = AdObjectGuidThreadLocal.getAndClear();

		try {
			User user = super.addUser(
				creatorUserId, companyId, autoPassword, password1, password2,
				autoScreenName, screenName, emailAddress, locale, firstName,
				middleName, lastName, prefixListTypeId, suffixListTypeId, male,
				birthdayMonth, birthdayDay, birthdayYear, jobTitle, type,
				groupIds, organizationIds, roleIds, userGroupIds, sendEmail,
				serviceContext);

			_seedAdObjectGuid(user, incomingAdObjectGuid);

			return user;
		}
		catch (PortalException portalException) {
			UserEmailAddressException.MustNotBeDuplicate duplicateException =
				_findDuplicateEmailException(portalException);

			if ((duplicateException == null) ||
				!_originatesFromLdapImport(serviceContext) ||
				!_deleteConflictingUser(
					companyId, screenName, incomingAdObjectGuid,
					duplicateException)) {

				throw portalException;
			}

			// Un unico reintento. Si vuelve a fallar, la excepcion sube tal
			// cual y el importador la trata como siempre.

			User user = super.addUser(
				creatorUserId, companyId, autoPassword, password1, password2,
				autoScreenName, screenName, emailAddress, locale, firstName,
				middleName, lastName, prefixListTypeId, suffixListTypeId, male,
				birthdayMonth, birthdayDay, birthdayYear, jobTitle, type,
				groupIds, organizationIds, roleIds, userGroupIds, sendEmail,
				serviceContext);

			_seedAdObjectGuid(user, incomingAdObjectGuid);

			return user;
		}
	}

	/**
	 * Este es el overload que {@code LDAPUserImporterImpl} llama en cada ciclo
	 * de sincronizacion para los usuarios existentes (verificado en el codigo
	 * fuente; ver README). Antes de aplicar los cambios, si ya hay un
	 * {@code objectGUID} guardado y el entrante es distinto, se bloquea la
	 * actualizacion: es otra persona que ha heredado el email y el screenName.
	 *
	 * <p>
	 * OJO: la firma exacta de este overload puede variar entre versiones de
	 * Liferay; compilar contra la build 2025.Q1 concreta.
	 * </p>
	 */
	@Override
	public User updateUser(
			long userId, String oldPassword, String newPassword1,
			String newPassword2, boolean passwordReset,
			String reminderQueryQuestion, String reminderQueryAnswer,
			String screenName, String emailAddress, boolean hasPortrait,
			byte[] portraitBytes, String languageId, String timeZoneId,
			String greeting, String comments, String firstName,
			String middleName, String lastName, long prefixListTypeId,
			long suffixListTypeId, boolean male, int birthdayMonth,
			int birthdayDay, int birthdayYear, String smsSn,
			String facebookSn, String jabberSn, String skypeSn,
			String twitterSn, String jobTitle, long[] groupIds,
			long[] organizationIds, long[] roleIds,
			List<UserGroupRole> userGroupRoles, long[] userGroupIds,
			ServiceContext serviceContext)
		throws PortalException {

		String incomingAdObjectGuid = AdObjectGuidThreadLocal.getAndClear();

		// Solo hay algo que comprobar si esta llamada trae un objectGUID (es
		// decir, procede del import LDAP). En cualquier otro updateUser del
		// portal no se hace ninguna lectura extra.

		if (Validator.isNotNull(incomingAdObjectGuid)) {
			User existingUser = fetchUser(userId);

			String storedAdObjectGuid = _getStoredAdObjectGuid(existingUser);

			if ((existingUser != null) &&
				Validator.isNotNull(storedAdObjectGuid) &&
				!storedAdObjectGuid.equals(incomingAdObjectGuid)) {

				// El registro ya tiene un objectGUID confirmado y no coincide
				// con el que llega ahora para el mismo screenName/email: es
				// una persona distinta. No pisamos sus datos.
				_log.warn(
					"Actualizacion LDAP bloqueada para el usuario " + userId +
						" (" + emailAddress + "): el objectGUID entrante (" +
							incomingAdObjectGuid + ") no coincide con el ya " +
								"registrado (" + storedAdObjectGuid + "). " +
									"Posible reciclaje de email y screenName " +
										"para una persona distinta -- " +
											"requiere revision manual.");

				return existingUser;
			}
		}

		User updatedUser = super.updateUser(
			userId, oldPassword, newPassword1, newPassword2, passwordReset,
			reminderQueryQuestion, reminderQueryAnswer, screenName,
			emailAddress, hasPortrait, portraitBytes, languageId, timeZoneId,
			greeting, comments, firstName, middleName, lastName,
			prefixListTypeId, suffixListTypeId, male, birthdayMonth,
			birthdayDay, birthdayYear, smsSn, facebookSn, jabberSn, skypeSn,
			twitterSn, jobTitle, groupIds, organizationIds, roleIds,
			userGroupRoles, userGroupIds, serviceContext);

		_seedAdObjectGuid(updatedUser, incomingAdObjectGuid);

		return updatedUser;
	}

	@Activate
	@Modified
	protected void activate(Map<String, Object> properties) {
		_dryRun = GetterUtil.getBoolean(properties.get("dryRun"), false);
		_protectAdministrators = GetterUtil.getBoolean(
			properties.get("protectAdministrators"), true);

		if (_log.isInfoEnabled()) {
			_log.info(
				"LDAPUserIdentitySyncWrapper activo: dryRun=" + _dryRun +
					", protectAdministrators=" + _protectAdministrators);
		}
	}

	private boolean _deleteConflictingUser(
		long companyId, String newScreenName, String incomingAdObjectGuid,
		UserEmailAddressException.MustNotBeDuplicate duplicateException) {

		String emailAddress = duplicateException.emailAddress;

		try {
			User conflictingUser = fetchUserByEmailAddress(
				companyId, emailAddress);

			if (conflictingUser == null) {
				_log.warn(
					"Email duplicado " + emailAddress + " pero no se " +
						"encuentra el usuario en la empresa " + companyId +
							"; no se hace nada");

				return false;
			}

			if (conflictingUser.isGuestUser()) {
				_log.warn(
					"El usuario en conflicto " + conflictingUser.getUserId() +
						" es el usuario guest; no se borra");

				return false;
			}

			if (_protectAdministrators &&
				_roleLocalService.hasUserRole(
					conflictingUser.getUserId(), companyId,
					RoleConstants.ADMINISTRATOR, true)) {

				_log.warn(
					"El usuario en conflicto " + conflictingUser.getUserId() +
						" (" + conflictingUser.getScreenName() +
							") es administrador; no se borra");

				return false;
			}

			// Solo informativo: si ambos objectGUID existen, indica si es la
			// misma persona (externo -> interno) o una distinta (email
			// reciclado). La politica es la misma en los dos casos: borrar.

			String storedAdObjectGuid = _getStoredAdObjectGuid(conflictingUser);

			String identity = "identidad sin determinar (falta objectGUID)";

			if (Validator.isNotNull(storedAdObjectGuid) &&
				Validator.isNotNull(incomingAdObjectGuid)) {

				identity = storedAdObjectGuid.equals(incomingAdObjectGuid) ?
					"misma persona (objectGUID coincide)" :
						"persona distinta (objectGUID no coincide)";
			}

			String description = String.format(
				"usuario en conflicto userId=%s screenName=%s email=%s " +
					"(nuevo screenName=%s, companyId=%s, %s)",
				conflictingUser.getUserId(), conflictingUser.getScreenName(),
				emailAddress, newScreenName, companyId, identity);

			if (_dryRun) {
				_log.warn("[dryRun] Se borraria el " + description);

				return false;
			}

			_log.warn(
				"Borrando el " + description + " y reintentando el alta");

			// ---------------------------------------------------------------
			// ALTERNATIVA (no activa): conservar el userId en vez de borrar.
			// ---------------------------------------------------------------
			//
			// En lugar de borrar al usuario en conflicto, se le cambia el
			// screenName por el nuevo (el parametro "screenName" de addUser,
			// que es el que llega del LDAP) y se DEVUELVE ese usuario en lugar
			// de crear otro. El importador LDAP hace a continuacion su
			// updateUser sobre el usuario devuelto, igual que si lo hubiera
			// creado. Se conservan el userId, los roles asignados a mano, el
			// contenido y las preferencias. Solo tiene sentido cuando el
			// objectGUID confirma que es la misma persona.
			//
			// Cambios necesarios (UserLocalService.updateScreenName(long,
			// String) existe con esa firma; sin compilar):
			//
			// 1. _deleteConflictingUser pasa a devolver el User (o null), en
			//    vez de un boolean, y addUser devuelve ese usuario sin
			//    reintentar super.addUser.
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

			return true;
		}
		catch (Exception exception) {
			_log.error(
				"No se pudo resolver el conflicto de email " + emailAddress,
				exception);

			return false;
		}
	}

	private UserEmailAddressException.MustNotBeDuplicate
		_findDuplicateEmailException(Throwable throwable) {

		// Recorre la cadena de causas por si alguna capa lo envolviera.

		int depth = 0;

		while ((throwable != null) && (depth < 10)) {
			if (throwable instanceof
					UserEmailAddressException.MustNotBeDuplicate) {

				return (UserEmailAddressException.MustNotBeDuplicate)throwable;
			}

			throwable = throwable.getCause();

			depth++;
		}

		return null;
	}

	/**
	 * Lee el {@code objectGUID} guardado para este usuario, o {@code null} si
	 * no hay usuario, el campo Expando no existe todavia, o no se ha guardado
	 * ningun valor.
	 */
	private String _getStoredAdObjectGuid(User user) {
		if (user == null) {
			return null;
		}

		ExpandoBridge expandoBridge = user.getExpandoBridge();

		if (!expandoBridge.hasAttribute(EXPANDO_COLUMN_AD_OBJECT_GUID)) {
			return null;
		}

		Object value = expandoBridge.getAttribute(
			EXPANDO_COLUMN_AD_OBJECT_GUID);

		if (value instanceof String) {
			return (String)value;
		}

		return null;
	}

	private boolean _originatesFromLdapImport(ServiceContext serviceContext) {
		if (serviceContext == null) {
			return false;
		}

		return serviceContext.getAttribute(_ATTRIBUTE_LDAP_SERVER_ID) != null;
	}

	/**
	 * Guarda el {@code objectGUID} en el campo Expando la primera vez que el
	 * usuario queda emparejado, si todavia no tenia uno. No sobrescribe un
	 * valor ya guardado: si llegase un objectGUID distinto para un usuario
	 * que ya tiene uno, lo detecta y bloquea el guard de {@link #updateUser},
	 * no este metodo, que solo "siembra" la primera vez.
	 *
	 * <p>
	 * Escribir en un campo Expando no requiere volver a llamar a
	 * {@code updateUser} para persistir el valor:
	 * {@link ExpandoBridge#setAttribute(String, java.io.Serializable)}
	 * persiste directamente. Confirmar este comportamiento contra la build
	 * 2025.Q1 antes de darlo por definitivo.
	 * </p>
	 */
	private void _seedAdObjectGuid(User user, String adObjectGuid) {
		if ((user == null) || Validator.isNull(adObjectGuid)) {
			return;
		}

		ExpandoBridge expandoBridge = user.getExpandoBridge();

		if (!expandoBridge.hasAttribute(EXPANDO_COLUMN_AD_OBJECT_GUID)) {
			_log.warn(
				"No existe el campo personalizado (Expando) '" +
					EXPANDO_COLUMN_AD_OBJECT_GUID + "' en User -- creadlo " +
						"en Instance Settings > Custom Fields antes de " +
							"desplegar este componente. No se ha guardado " +
								"el objectGUID del usuario " +
									user.getUserId() + ".");

			return;
		}

		if (Validator.isNotNull(_getStoredAdObjectGuid(user))) {
			return;
		}

		expandoBridge.setAttribute(EXPANDO_COLUMN_AD_OBJECT_GUID, adObjectGuid);
	}

	private static final String _ATTRIBUTE_LDAP_SERVER_ID = "ldapServerId";

	private static final Log _log = LogFactoryUtil.getLog(
		LDAPUserIdentitySyncWrapper.class);

	private volatile boolean _dryRun;
	private volatile boolean _protectAdministrators = true;

	@Reference
	private RoleLocalService _roleLocalService;

}
