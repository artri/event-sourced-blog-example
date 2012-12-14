package controllers

import events._
import eventstore._
import eventstore.Transaction._
import models._
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import support.Forms._

object UsersController extends UsersController(Global.MemoryImageActions.view(_.users), Global.emailAddressRegistry)
class UsersController(actions: ApplicationActions[Users, UserEvent], registerEmailAddress: EmailAddress => UserId) {
  import actions._

  object authentication {
    val loginForm: Form[(EmailAddress, String)] = Form(tuple(
      "email" -> email.transform[EmailAddress](EmailAddress.apply, _.value),
      "password" -> text.transform[String](identity, _ => "")))

    def show = ApplicationAction { implicit request => Ok(views.html.users.log_in(loginForm)) }

    def submit = CommandAction { users => implicit request =>
      val form = loginForm.bindFromRequest
      form.fold(
        formWithErrors =>
          abort(BadRequest(views.html.users.log_in(formWithErrors))),
        credentials =>
          users.authenticate(credentials._1, credentials._2) map {
            case (user, token) =>
              Changes(user.revision, UserLoggedIn(user.id, token): UserEvent).commit(
                onCommit = Redirect(routes.UsersController.authentication.loggedIn).withSession("authenticationToken" -> token.toString),
                onConflict = conflict => sys.error("impossible conflict: " + conflict))
          } getOrElse {
            abort(BadRequest(views.html.users.log_in(form.addError(FormError("", "bad.credentials")))))
          })
    }

    def logOut = CommandAction { state => implicit request =>
      request.currentUser.registered.map { user =>
        Changes(user.revision, UserLoggedOut(user.id): UserEvent).commit(
          onCommit = Redirect(routes.UsersController.authentication.loggedOut).withNewSession,
          onConflict = conflict => sys.error("impossible conflict: " + conflict))
      } getOrElse {
        abort(Redirect(routes.UsersController.authentication.loggedOut).withNewSession)
      }
    }

    def loggedIn = AuthenticatedQueryAction { _ => _ => implicit request => Ok(views.html.users.logged_in()) }
    def loggedOut = ApplicationAction { implicit request => Ok(views.html.users.logged_out()) }
  }

  object register {
    val confirmPasswordMapping: Mapping[Password] = tuple(
      "1" -> text.verifying(minLength(8)),
      "2" -> text).
      verifying("error.password.mismatch", fields => fields._1 == fields._2).
      transform(fields => Password.fromPlainText(fields._1), _ => ("", ""))

    /**
     * Registration form.
     */
    val registrationForm: Form[(EmailAddress, String, Password)] = Form(tuple(
      "email" -> email.transform[EmailAddress](EmailAddress.apply, _.value),
      "displayName" -> tokenizedText.verifying(minLength(2)),
      "password" -> confirmPasswordMapping))

    def show = ApplicationAction { implicit request =>
      Ok(views.html.users.register(registrationForm))
    }
    def submit = CommandAction { state => implicit request =>
      val form = registrationForm.bindFromRequest
      form.fold(
        formWithErrors =>
          abort(BadRequest(views.html.users.register(formWithErrors))),
        registration => {
          val (email, displayName, password) = registration
          val userId = registerEmailAddress(email)
          Changes(StreamRevision.Initial, UserRegistered(userId, email, displayName, password): UserEvent).commit(
            onCommit = Redirect(routes.UsersController.register.registered),
            onConflict = _ => BadRequest(views.html.users.register(form.addError(FormError("", "duplicate.account")))))
        })
    }
    def registered = ApplicationAction { implicit request =>
      Ok(views.html.users.registered())
    }
  }
}
