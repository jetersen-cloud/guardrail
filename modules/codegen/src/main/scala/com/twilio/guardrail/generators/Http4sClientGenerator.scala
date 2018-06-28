package com.twilio.guardrail
package generators

import _root_.io.swagger.models._
import cats.arrow.FunctionK
import cats.data.NonEmptyList
import cats.instances.all._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import com.twilio.guardrail.extract.ScalaPackage
import com.twilio.guardrail.terms.RouteMeta
import java.util.Locale

import com.twilio.guardrail.protocol.terms.client._

import scala.collection.JavaConverters._
import scala.meta._

object Http4sClientGenerator {

  object ClientTermInterp extends FunctionK[ClientTerm, Target] {
    def splitOperationParts(operationId: String): (List[String], String) = {
      val parts = operationId.split('.')
      (parts.drop(1).toList, parts.last)
    }

    private[this] def toDashedCase(s: String): String = {
      val lowercased =
        "^([A-Z])".r.replaceAllIn(s, m => m.group(1).toLowerCase(Locale.US))
      "([A-Z])".r
        .replaceAllIn(lowercased, m => '-' +: m.group(1).toLowerCase(Locale.US))
    }

    private[this] def formatClientName(clientName: Option[String]): Term.Param =
      clientName.fold(
        param"clientName: String"
      )(name => param"clientName: String = ${Lit.String(toDashedCase(name))}")

    private[this] def formatHost(schemes: List[String], host: Option[String]): Term.Param =
      host
        .map {
          case v if !v.startsWith("http") =>
            val scheme = schemes.headOption.getOrElse("http")
            s"${scheme}://${v}"
          case v => v
        }
        .fold(param"host: String")(v => param"host: String = ${Lit.String(v)}")

    def apply[T](term: ClientTerm[T]): Target[T] = term match {
      case GenerateClientOperation(className, RouteMeta(pathStr, httpMethod, operation), tracing, protocolElems) =>
        def generateUrlWithParams(path: String, pathArgs: List[ScalaParameter], qsArgs: List[ScalaParameter]): Target[Term] =
          for {
            _    <- Target.log.debug("generateClientOperation", "generateUrlWithParams")(s"Using ${path} and ${pathArgs.map(_.argName)}")
            base <- SwaggerUtil.paths.generateUrlPathParams(path, pathArgs)

            _ <- Target.log.debug("generateClientOperation", "generateUrlWithParams")(s"QS: ${qsArgs}")

            suffix = if (path.contains("?")) {
              Lit.String("&")
            } else {
              Lit.String("?")
            }

            _ <- Target.log.debug("generateClientOperation", "generateUrlWithParams")(s"QS: ${qsArgs}")

            result = NonEmptyList
              .fromList(qsArgs.toList)
              .fold(base)({
                _.foldLeft[Term](q"${base} + ${suffix}") {
                  case (a, ScalaParameter(_, _, paramName, argName, _)) =>
                    q""" $a + Formatter.addArg(${Lit
                      .String(argName.value)}, ${paramName})"""
                }
              })
          } yield q"Uri.unsafeFromString(${result})"

        def generateFormDataParams(parameters: List[ScalaParameter], needsMultipart: Boolean): Option[Term] =
          if (parameters.isEmpty) {
            None
          } else if (needsMultipart) {
            def liftOptionFileTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"$tParamName.map(v => Part.fileData[F](${tName.toLit}, v))"

            def liftFileTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"Some(Part.fileData[F](${tName.toLit}, ${tParamName}))"

            def liftOptionTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"$tParamName.map(v => Part.formData[F](${tName.toLit}, Formatter.show(v)))"

            def liftTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"Some(Multipart(${tName.toLit}, Formatter.show($tParamName)))"

            val args: List[Term] = parameters.foldLeft(List.empty[Term]) {
              case (a, ScalaParameter(_, param, paramName, argName, _)) =>
                val lifter: (Term.Name, RawParameterName) => Term = {
                  param match {
                    case param"$_: Option[java.io.File]" =>
                      liftOptionFileTerm _
                    case param"$_: Option[java.io.File] = $_" =>
                      liftOptionFileTerm _
                    case param"$_: java.io.File"      => liftFileTerm _
                    case param"$_: java.io.File = $_" => liftFileTerm _
                    case param"$_: Option[$_]"        => liftOptionTerm _
                    case param"$_: Option[$_] = $_"   => liftOptionTerm _
                    case _                            => liftTerm _
                  }
                }
                a :+ lifter(paramName, argName)
            }
            Some(q"List(..$args)")
          } else {
            def liftOptionTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"(${tName.toLit}, $tParamName.map(Formatter.show(_)))"

            def liftTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"(${tName.toLit}, Some(Formatter.show($tParamName)))"

            val args: List[Term] = parameters.foldLeft(List.empty[Term]) {
              case (a, ScalaParameter(_, param, paramName, argName, _)) =>
                val lifter: (Term.Name, RawParameterName) => Term =
                  param match {
                    case param"$_: Option[$_]"      => liftOptionTerm _
                    case param"$_: Option[$_] = $_" => liftOptionTerm _
                    case _                          => liftTerm _
                  }
                a :+ lifter(paramName, argName)
            }
            Some(q"List(..$args)")
          }

        def generateHeaderParams(parameters: List[ScalaParameter]): Term = {
          def liftOptionTerm(tParamName: Term.Name, tName: RawParameterName) =
            q"$tParamName.map(v => Header(${tName.toLit}, Formatter.show(v)))"

          def liftTerm(tParamName: Term.Name, tName: RawParameterName) =
            q"Some(Header(${tName.toLit}, Formatter.show($tParamName)))"

          val args: List[Term] = parameters.foldLeft(List.empty[Term]) {
            case (a, ScalaParameter(_, param, paramName, argName, _)) =>
              val lifter: (Term.Name, RawParameterName) => Term = param match {
                case param"$_: Option[$_]"      => liftOptionTerm _
                case param"$_: Option[$_] = $_" => liftOptionTerm _
                case _                          => liftTerm _
              }
              a :+ lifter(paramName, argName)
          }
          q"List[Option[Header]](..$args).flatten"
        }

        def build(methodName: String,
                  httpMethod: HttpMethod,
                  urlWithParams: Term,
                  formDataParams: Option[Term],
                  formDataNeedsMultipart: Boolean,
                  headerParams: Term,
                  responseTypeRef: Type,
                  tracing: Boolean)(tracingArgsPre: List[ScalaParameter],
                                    tracingArgsPost: List[ScalaParameter],
                                    pathArgs: List[ScalaParameter],
                                    qsArgs: List[ScalaParameter],
                                    formArgs: List[ScalaParameter],
                                    body: Option[ScalaParameter],
                                    headerArgs: List[ScalaParameter],
                                    extraImplicits: List[Term.Param]): Defn = {
          val implicitParams = Option(extraImplicits).filter(_.nonEmpty)
          val defaultHeaders = param"headers: List[Header] = List.empty"
          val safeBody: Option[(Term, Type)] =
            body.map(sp => (sp.argName.toLit, sp.argType))

          val formEntity: Option[Term] = formDataParams.map { formDataParams =>
            if (formDataNeedsMultipart) {
              q"""Multipart($formDataParams.flatten.toVector)"""
            } else {
              q"""UrlForm($formDataParams.collect({ case (n, Some(v)) => (n, v) }): _*)"""
            }
          }

          val entity: Term =
            formEntity.orElse(safeBody.map(_._1)).getOrElse(q"EmptyBody")
          val methodBody: Term = if (tracing) {
            val tracingLabel = q"""s"$${clientName}:$${methodName}""""
            q"""
            {
              val tracingHttpClient = traceBuilder(s"$${clientName}:$${methodName}")(httpClient)
              val allHeaders = headers ++ $headerParams
              /* wrap[{responseTypeRef}]( */
                tracingHttpClient.expect[${responseTypeRef}](Request[F](method = Method.${Term
              .Name(httpMethod.toString.toUpperCase)}, uri = ${urlWithParams}, headers = Headers(allHeaders)).withBody(${entity}))
              /* ) */
            }
            """
          } else {
            q"""
            {
              val allHeaders = headers ++ $headerParams
              // wrap[{responseTypeRef}](
                httpClient.expect[${responseTypeRef}](Request[F](method = Method.${Term
              .Name(httpMethod.toString.toUpperCase)}, uri = ${urlWithParams}, headers = Headers(allHeaders)).withBody(${entity}))
              // )
            }
            """
          }

          val arglists: List[List[Term.Param]] = List(
            Some(
              (tracingArgsPre.map(_.param) ++ pathArgs.map(_.param) ++ qsArgs
                .map(_.param) ++ formArgs.map(_.param) ++ body
                .map(_.param) ++ headerArgs.map(_.param) ++ tracingArgsPost
                .map(_.param)) :+ defaultHeaders
            ),
            implicitParams
          ).flatten

          q"""
          def ${Term
            .Name(methodName)}(...${arglists}): F[$responseTypeRef] = $methodBody
          """
        }

        Target.getGeneratorSettings.flatMap { implicit gs =>
          for {
            // Placeholder for when more functions get logging
            _ <- Target.pure(())

            formDataNeedsMultipart = Option(operation.getConsumes)
              .exists(_.contains("multipart/form-data"))

            // Get the response type
            unresolvedResponseTypeRef <- SwaggerUtil.getResponseType(httpMethod, operation)
            resolvedResponseTypeRef <- SwaggerUtil.ResolvedType
              .resolve(unresolvedResponseTypeRef, protocolElems)
            responseTypeRef = resolvedResponseTypeRef.tpe

            // Insert the method parameters
            httpMethodStr: String = httpMethod.toString.toLowerCase
            methodName = Option(operation.getOperationId())
              .map(splitOperationParts)
              .map(_._2)
              .getOrElse(s"$httpMethodStr $pathStr")

            _ <- Target.log.debug("generateClientOperation")(s"Parsing: ${httpMethodStr} ${methodName}")

            allParams <- Option(operation.getParameters)
              .map(_.asScala.toList)
              .map(ScalaParameter.fromParameters(protocolElems))
              .getOrElse(Target.pure(List.empty[ScalaParameter]))
            _ <- Target.log.debug("generateClientOperation")(s"Unfiltered params: ${allParams}")

            filterParamBy = ScalaParameter.filterParams(allParams)
            headerArgs    = filterParamBy("header")
            pathArgs      = filterParamBy("path")
            qsArgs        = filterParamBy("query")
            bodyArgs      = filterParamBy("body").headOption
            formArgs      = filterParamBy("formData")

            _ <- Target.log.debug("generateClientOperation")(s"pathArgs: ${pathArgs}")

            // Generate the url with path, query parameters
            urlWithParams <- generateUrlWithParams(pathStr, pathArgs, qsArgs)

            _ <- Target.log.debug("generateClientOperation")(s"Generated: ${urlWithParams}")
            // Generate FormData arguments
            formDataParams = generateFormDataParams(formArgs, formDataNeedsMultipart)
            // Generate header arguments
            headerParams = generateHeaderParams(headerArgs)

            tracingArgsPre = if (tracing)
              List(ScalaParameter.fromParam(param"traceBuilder: TraceBuilder[F]"))
            else List.empty
            tracingArgsPost = if (tracing)
              List(ScalaParameter.fromParam(param"methodName: String = ${Lit.String(toDashedCase(methodName))}"))
            else List.empty
            extraImplicits = List.empty
            defn = build(methodName, httpMethod, urlWithParams, formDataParams, formDataNeedsMultipart, headerParams, responseTypeRef, tracing)(tracingArgsPre,
                                                                                                                                                tracingArgsPost,
                                                                                                                                                pathArgs,
                                                                                                                                                qsArgs,
                                                                                                                                                formArgs,
                                                                                                                                                bodyArgs,
                                                                                                                                                headerArgs,
                                                                                                                                                extraImplicits)
          } yield defn
        }

      case GetImports(tracing) => Target.pure(List.empty)

      case GetExtraImports(tracing) => Target.pure(List.empty)

      case ClientClsArgs(tracingName, schemes, host, tracing) =>
        val ihc = param"implicit httpClient: Client[F]"
        val ief = param"implicit effect: Effect[F]"
        Target.pure(
          List(List(formatHost(schemes, host)) ++ (if (tracing)
                                                     Some(formatClientName(tracingName))
                                                   else None),
               List(ief, ihc))
        )

      case BuildCompanion(clientName, tracingName, schemes, host, ctorArgs, tracing) =>
        def extraConstructors(tracingName: Option[String],
                              schemes: List[String],
                              host: Option[String],
                              tpe: Type.Name,
                              ctorCall: Term.New,
                              tracing: Boolean): List[Defn] = {
          val tracingParams: List[Term.Param] = if (tracing) {
            List(formatClientName(tracingName))
          } else {
            List.empty
          }

          List(
            q"""
              def httpClient[F[_]](effect: Effect[F], httpClient: Client[F], ${formatHost(schemes, host)}, ..${tracingParams}): ${tpe}[F] = ${ctorCall}
            """
          )
        }

        def paramsToArgs(params: List[List[Term.Param]]): List[List[Term]] =
          params
            .map({
              _.map(_.name.value)
                .map(v => Term.Assign(Term.Name(v), Term.Name(v)))
                .to[List]
            })
            .to[List]

        val ctorCall: Term.New = {
          q"""
            new ${Type
            .Apply(Type.Name(clientName), List(Type.Name("F")))}(...${paramsToArgs(ctorArgs)})
          """
        }

        val companion: Defn.Object =
          q"""
            object ${Term.Name(clientName)} {
              def apply[F[_]](...${ctorArgs}): ${Type
            .Apply(Type.Name(clientName), List(Type.Name("F")))} = ${ctorCall}
              ..${extraConstructors(tracingName, schemes, host, Type.Name(clientName), ctorCall, tracing)}
            }
          """
        Target.pure(companion)

      case BuildClient(clientName, tracingName, schemes, host, basePath, ctorArgs, clientCalls, tracing) =>
        val client =
          q"""
            class ${Type.Name(clientName)}[F[_]](...${ctorArgs}) {
              val basePath: String = ${Lit.String(basePath.getOrElse(""))}
              /*
              private[this] def wrap[T: EntityDecoder](resp: Response[IO]): IO[T] = {
                resp.flatMap(resp =>
                  if (resp.status.isSuccess) {
                    resp.as[T].map(Right.apply _)
                  } else {
                    IO.pure(Left(Right(resp)))
                  }
                ).handle {
                  case e: Throwable => Left(Left(e))
                }
              }
              */

              ..$clientCalls
            }
          """
        Target.pure(client)
    }
  }

}
