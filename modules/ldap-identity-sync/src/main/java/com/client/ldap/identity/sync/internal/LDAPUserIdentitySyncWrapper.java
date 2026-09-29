package com.client.ldap.identity.sync.internal;

import com.liferay.expando.kernel.model.ExpandoBridge;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.UserEmailAddressException;
import com.liferay.portal.kernel.exception.UserScreenNameException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.model.UserGroupRole;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceWrapper;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.kernel.service.UserLocalServiceWrapper;
import com.liferay.portal.kernel.util.Validator;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Resuelve, a nivel de servicio, los dos escenarios de colision reportados en
 * la sincronizacion LDAP -> Liferay del cliente:
 *
 * <ol>
 *   <li><b>Externo pasa a interno.</b> El screenName cambia en AD, el import
 *       LDAP no encuentra al usuario por su criterio habitual (screenName o
 *       email, segun {@code company.security.auth.type}) e intenta CREAR un
 *       usuario nuevo. La creacion falla con
 *       {@link UserEmailAddressException.MustNotBeDuplicate} (o
 *       {@link UserScreenNameException.MustNotBeDuplicate}) porque el email
 *       ya existe en el registro antiguo.</li>
 *   <li><b>Email reciclado para otra persona.</b> AD reutiliza un email (y,
 *       si el screenName se genera a partir del nombre, puede que tambien
 *       coincida el screenName) para un empleado distinto. Aqui NO hay
 *       excepcion: el import LDAP encuentra "correctamente" al usuario
 *       antiguo por su criterio de matching y lo actualiza con los datos de
 *       la persona equivocada.</li>
 * </ol>
 *
 * Estrategia:
 *
 * <ul>
 *   <li>Se guarda el {@code objectGUID} de Active Directory como
 *       identificador estable de la identidad, en un campo Expando propio del
 *       User ({@value #EXPANDO_COLUMN_AD_OBJECT_GUID}). El valor "entrante"
 *       llega desde {@link ADObjectGuidAttributesTransformer} via
 *       {@link AdObjectGuidThreadLocal} -- NO via
 *       {@code serviceContext.getUuid()} (ver el javadoc de
 *       {@link AdObjectGuidThreadLocal} para el porque: ese mecanismo nativo
 *       solo funciona con "Import User Sync Strategy = UUID", no con
 *       "Auth Type", que es la que se usa aqui a proposito). Ver README para
 *       el porque de usar un campo Expando propio y no
 *       {@code User.externalReferenceCode} ni el {@code uuid_} interno del
 *       User.</li>
 *   <li>En {@link #addUser}, si la creacion falla por duplicado, se
 *       comprueba si el conflicto es en realidad el mismo usuario (escenario
 *       1) y, si es asi, se actualiza el registro existente en vez de
 *       propagar el error.</li>
 *   <li>En {@link #updateUser(long, String, String, String, boolean, String,
 *       String, String, String, boolean, byte[], String, String, String,
 *       String, String, String, String, long, long, boolean, int, int, int,
 *       String, String, String, String, String, String, long[], long[],
 *       long[], List, long[], ServiceContext) el overload correcto}, antes de
 *       aplicar los cambios se comprueba que el {@code objectGUID} entrante
 *       coincide con el que ya estaba guardado (si lo habia). Si no coincide,
 *       se trata como escenario 2 y se bloquea la actualizacion automatica en
 *       vez de sobrescribir la identidad.</li>
 * </ul>
 *
 * <p>Nota sobre el overload de {@code updateUser} sobreescrito: no es
 * {@code updateUser(User user)}, el mas simple de {@link UserLocalService}.
 * Se ha verificado en el codigo fuente real de {@code LDAPUserImporterImpl}
 * (modulo {@code portal-security-ldap}) que el importador LDAP llama al
 * overload "clasico" de muchos parametros posicionales que termina en
 * {@code ServiceContext}. Ver README, seccion "Overloads de UserLocalService
 * que intercepta".</p>
 *
 * IMPORTANTE -- este componente es un punto de partida, no un artefacto
 * listo para produccion sin revision. Ver README.md para:
 * <ul>
 *   <li>la lista de supuestos que hay que validar contra la version 2025.Q1
 *       concreta (firma exacta de este overload de {@code updateUser} en
 *       vuestro javadoc, comportamiento de {@code ExpandoBridge});</li>
 *   <li>la politica a definir para el escenario 2 (hoy solo se registra y se
 *       bloquea; decidir si conviene, por ejemplo, liberar el email del
 *       usuario antiguo automaticamente);</li>
 *   <li>el plan de pruebas antes de desplegar en produccion.</li>
 * </ul>
 */
@Component(immediate = true, property = {}, service = ServiceWrapper.class)
public class LDAPUserIdentitySyncWrapper extends UserLocalServiceWrapper {

	/**
	 * Nombre del campo Expando (custom field) del User donde se guarda el
	 * {@code objectGUID} de Active Directory. Hay que crearlo antes de
	 * desplegar este componente, en Instance Settings > Custom Fields (User)
	 * -- ver README. Si el campo no existe todavia,
	 * {@link ExpandoBridge#setAttribute(String, java.io.Serializable)} no
	 * tiene donde guardar el valor: por eso {@link #_seedAdObjectGuid}
	 * comprueba {@code hasAttribute} antes de escribir y avisa en el log si
	 * falta.
	 */
	private static final String EXPANDO_COLUMN_AD_OBJECT_GUID = "adObjectGUID";

	public LDAPUserIdentitySyncWrapper() {
		super(null);
	}

	/**
	 * Es este metodo, no el constructor, el que realmente engancha el
	 * wrapper al {@link UserLocalService} real. Sin el (y sin registrar el
	 * componente como {@code service = ServiceWrapper.class}), Liferay
	 * despliega y activa el componente sin errores, pero el
	 * {@code ServiceProxyFactory} nunca lo encadena delante del servicio
	 * real -- se queda inerte, sin interceptar nada, y sin ningun error que
	 * lo delate.
	 */
	@Reference(unbind = "-")
	protected void setUserLocalService(UserLocalService userLocalService) {
		setWrappedService(userLocalService);
	}

	@Override
	public User addUser(
			long creatorUserId, long companyId, boolean autoPassword,
			String password1, String password2, boolean autoScreenName,
			String screenName, String emailAddress, Locale locale,
			String firstName, String middleName, String lastName,
			long prefixListTypeId, long suffixListTypeId, boolean male,
			int birthdayMonth, int birthdayDay, int birthdayYear,
			String jobTitle, int type, long[] groupIds,
			long[] organizationIds, long[] roleIds, long[] userGroupIds,
			boolean sendEmail, ServiceContext serviceContext)
		throws PortalException {

		// El objectGUID de esta misma entrada LDAP lo dejo
		// ADObjectGuidAttributesTransformer en el ThreadLocal justo antes de
		// que el import LDAP llegue hasta aqui -- ver su javadoc para el
		// porque no se usa serviceContext.getUuid() para esto.
		String incomingAdObjectGuid = AdObjectGuidThreadLocal.getAndClear();

		try {
			User user = super.addUser(
				creatorUserId, companyId, autoPassword, password1, password2,
				autoScreenName, screenName, emailAddress, locale, firstName,
				middleName, lastName, prefixListTypeId, suffixListTypeId,
				male, birthdayMonth, birthdayDay, birthdayYear, jobTitle,
				type, groupIds, organizationIds, roleIds, userGroupIds,
				sendEmail, serviceContext);

			_seedAdObjectGuid(user, incomingAdObjectGuid);

			return user;
		}
		catch (UserEmailAddressException.MustNotBeDuplicate |
			   UserScreenNameException.MustNotBeDuplicate
				   duplicateException) {

			User resolvedUser = _resolveCreateConflict(
				companyId, emailAddress, screenName, firstName, lastName,
				incomingAdObjectGuid);

			if (resolvedUser == null) {

				// No es el escenario 1 (mismo usuario, atributos cambiados):
				// es un duplicado real y ajeno a este mecanismo. No lo
				// silenciamos.
				throw duplicateException;
			}

			return resolvedUser;
		}
	}

	/**
	 * Este es el overload que {@code LDAPUserImporterImpl} llama realmente
	 * en cada ciclo de sincronizacion, tanto para altas como para usuarios
	 * existentes (verificado en el codigo fuente -- ver README). A
	 * diferencia de {@code updateUser(User user)}, este SI lo llama el import
	 * LDAP para cada entrada procesada.
	 *
	 * <p>OJO: la firma exacta de este overload (tipos, orden y numero de
	 * parametros de los campos de redes sociales tipo {@code smsSn}) puede
	 * variar ligeramente entre versiones de Liferay. Comprobad esta firma
	 * contra el javadoc de {@code UserLocalService} de vuestra build 2025.Q1
	 * exacta antes de compilar -- ver README, "Supuestos que hay que
	 * validar".</p>
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

		User existingUser = fetchUser(userId);

		// Igual que en addUser: el objectGUID de esta entrada LDAP viene del
		// ThreadLocal que deja ADObjectGuidAttributesTransformer, no de
		// serviceContext.getUuid().
		String incomingAdObjectGuid = AdObjectGuidThreadLocal.getAndClear();

		String storedAdObjectGuid = _getStoredAdObjectGuid(existingUser);

		if ((existingUser != null) && Validator.isNotNull(storedAdObjectGuid) &&
			Validator.isNotNull(incomingAdObjectGuid) &&
			!storedAdObjectGuid.equals(incomingAdObjectGuid)) {

			// Escenario 2: el registro ya tiene un objectGUID confirmado y no
			// coincide con el que esta llegando ahora para el mismo
			// email/screenName. Es una persona distinta que ha heredado el
			// email -- no pisamos sus datos.
			_log.warn(
				"Actualizacion LDAP bloqueada para el usuario " + userId +
					" (" + emailAddress + "): el objectGUID entrante (" +
						incomingAdObjectGuid + ") no coincide con el ya " +
							"registrado (" + storedAdObjectGuid + "). " +
								"Posible reciclaje de email para una " +
									"persona distinta -- requiere revision " +
										"manual.");

			return existingUser;
		}

		User updatedUser = super.updateUser(
			userId, oldPassword, newPassword1, newPassword2, passwordReset,
			reminderQueryQuestion, reminderQueryAnswer, screenName,
			emailAddress, hasPortrait, portraitBytes, languageId,
			timeZoneId, greeting, comments, firstName, middleName, lastName,
			prefixListTypeId, suffixListTypeId, male, birthdayMonth,
			birthdayDay, birthdayYear, smsSn, facebookSn, jabberSn, skypeSn,
			twitterSn, jobTitle, groupIds, organizationIds, roleIds,
			userGroupRoles, userGroupIds, serviceContext);

		_seedAdObjectGuid(updatedUser, incomingAdObjectGuid);

		return updatedUser;
	}

	/**
	 * Escenario 1: intenta encontrar, entre los usuarios existentes con el
	 * mismo email que provoco el duplicado, uno que razonablemente sea "la
	 * misma persona" a la que el import LDAP simplemente le esta trayendo un
	 * screenName/atributos nuevos (el caso tipico de externo -> interno).
	 *
	 * @return el usuario ya actualizado si se resolvio el conflicto como el
	 *         mismo usuario, o {@code null} si no se pudo determinar con
	 *         confianza (en cuyo caso el llamante debe propagar la excepcion
	 *         original en vez de asumir nada).
	 */
	private User _resolveCreateConflict(
			long companyId, String emailAddress, String incomingScreenName,
			String firstName, String lastName, String incomingAdObjectGuid)
		throws PortalException {

		User existingUser = fetchUserByEmailAddress(companyId, emailAddress);

		if (existingUser == null) {

			// TODO(cliente): placeholder para el escenario hermano del 2,
			// pero por screenName en vez de por email: que un numero de
			// expediente antiguo (p.ej. "E12345", ya liberado al pasar su
			// titular a interno como "U67890") se reutilice para dar de
			// alta a un externo distinto. Hoy esa colision NO se resuelve
			// aqui -- si el import LDAP falla con
			// UserScreenNameException.MustNotBeDuplicate sin colision de
			// email (o sea,
			// llegamos aqui via ese catch pero sin encontrar nada por
			// email), simplemente se devuelve null y el llamante propaga la
			// excepcion original. No rompe nada, pero tampoco lo soluciona
			// solo.
			//
			// Si en el futuro se confirma que este patron es real en
			// vuestro entorno, implementar aqui una logica analoga a la de
			// email:
			//   1. User existingUserByScreenName =
			//        fetchUserByScreenName(companyId, incomingScreenName);
			//   2. Si no es null, comparar su objectGUID guardado (via el
			//      mismo campo Expando, _getStoredAdObjectGuid) y
			//      nombre/apellidos igual que se hace mas abajo para el
			//      caso de email.
			//   3. Si el objectGUID existente NO coincide con el entrante,
			//      es un expediente reciclado para otra persona -- no
			//      reconciliar automaticamente, solo registrar un WARN para
			//      revision manual (mismo criterio que el escenario 2).
			//   4. Si coincide (o el existente no tenia objectGUID todavia)
			//      y el nombre/apellidos son plausibles, sera un caso
			//      distinto de "misma persona" al de hoy -- pensar entonces
			//      que hacer con el screenName/email resultante antes de
			//      reutilizar tal cual la logica de mas abajo.
			return null;
		}

		String existingAdObjectGuid = _getStoredAdObjectGuid(existingUser);

		// Solo tratamos esto como "es la misma persona" cuando:
		//   a) el registro existente todavia no tiene un objectGUID
		//      confirmado (nunca se ha resuelto un conflicto de este tipo
		//      para el), o
		//   b) el objectGUID coincide exactamente con el que trae la nueva
		//      entrada de LDAP.
		// Si el objectGUID existente esta poblado y NO coincide, no es el
		// mismo usuario -- es el escenario 2 colandose por la via de
		// creacion, y ahi NO tocamos nada automaticamente.
		boolean sameIdentityByAdObjectGuid =
			Validator.isNull(existingAdObjectGuid) ||
			(Validator.isNotNull(incomingAdObjectGuid) &&
				existingAdObjectGuid.equals(incomingAdObjectGuid));

		if (!sameIdentityByAdObjectGuid) {
			_log.warn(
				"Conflicto de creacion LDAP para el email " + emailAddress +
					": el usuario existente " + existingUser.getUserId() +
						" tiene un objectGUID (" + existingAdObjectGuid +
							") distinto del entrante (" +
								incomingAdObjectGuid + "). No se fusiona " +
									"automaticamente -- requiere revision " +
										"manual.");

			return null;
		}

		// TODO(cliente): si algun dia disponeis de un identificador de negocio
		// mas fuerte que nombre+apellidos (numero de expediente, DNI...),
		// comprobadlo aqui tambien antes de dar por buena la fusion. Con
		// solo nombre+apellidos hay riesgo de falso positivo si dos personas
		// distintas comparten nombre completo.
		if (!_isPlausibleSamePerson(existingUser, firstName, lastName)) {
			_log.warn(
				"Conflicto de creacion LDAP para el email " + emailAddress +
					": nombre/apellidos entrantes (" + firstName + " " +
						lastName + ") no coinciden con los del usuario " +
							"existente " + existingUser.getUserId() +
								". No se fusiona automaticamente.");

			return null;
		}

		_log.info(
			"Reconciliando usuario " + existingUser.getUserId() +
				" (email " + emailAddress + ") con nuevo screenName '" +
					incomingScreenName + "' en vez de crear un duplicado.");

		existingUser.setScreenName(incomingScreenName);

		// Llamada directa al servicio envuelto (no a este wrapper): aqui
		// solo estamos persistiendo el screenName ya corregido de un
		// usuario cuya identidad ya hemos verificado arriba, no un update
		// mas que deba pasar otra vez por el guard del escenario 2.
		User updatedUser = super.updateUser(existingUser);

		_seedAdObjectGuid(updatedUser, incomingAdObjectGuid);

		return updatedUser;
	}

	private boolean _isPlausibleSamePerson(
		User existingUser, String firstName, String lastName) {

		return Objects.equals(existingUser.getFirstName(), firstName) &&
			Objects.equals(existingUser.getLastName(), lastName);
	}

	/**
	 * Lee el {@code objectGUID} guardado para este usuario, o {@code null} si
	 * no hay usuario, el campo Expando no existe todavia, o no se ha
	 * guardado ningun valor.
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

	/**
	 * Guarda el {@code objectGUID} en el campo Expando {@value
	 * #EXPANDO_COLUMN_AD_OBJECT_GUID} la primera vez que el usuario queda
	 * emparejado con confianza, si todavia no tenia uno. No se sobreescribe
	 * un valor ya guardado: si en algun momento llegase un objectGUID
	 * distinto para un usuario que ya tiene uno confirmado, eso lo debe
	 * detectar (y bloquear) el guard de {@link #updateUser}, no este metodo
	 * -- este metodo solo "siembra" la primera vez.
	 *
	 * <p>Escribir en un campo Expando no requiere volver a llamar a
	 * {@code updateUser} para persistir el valor:
	 * {@link ExpandoBridge#setAttribute(String, java.io.Serializable)}
	 * persiste directamente contra la tabla de valores Expando. Confirmar
	 * este comportamiento empiricamente contra vuestra build 2025.Q1 antes
	 * de dar esto por definitivo -- ver README.</p>
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

	private static final Log _log = LogFactoryUtil.getLog(
		LDAPUserIdentitySyncWrapper.class);

}
