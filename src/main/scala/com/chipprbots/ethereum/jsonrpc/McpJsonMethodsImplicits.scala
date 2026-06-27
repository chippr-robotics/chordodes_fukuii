package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST.*
import org.json4s.JsonDSL.*
import org.json4s.jvalue2extractable
import org.json4s.jvalue2monadic

import com.chipprbots.ethereum.jsonrpc.McpService.*
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodCodec
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder

object McpJsonMethodsImplicits extends JsonMethodsImplicits:

  // Decoders
  given mcpInitializeRequestDecoder: JsonMethodDecoder[McpInitializeRequest] =
    new JsonMethodDecoder[McpInitializeRequest]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpInitializeRequest] =
        params match
          case Some(JArray(obj :: Nil)) => Right(McpInitializeRequest(Some(obj)))
          case Some(JArray(Nil))        => Right(McpInitializeRequest(None))
          case None                     => Right(McpInitializeRequest(None))
          case _                        => Left(JsonRpcError.InvalidParams())

  given mcpToolsListRequestDecoder: JsonMethodDecoder[McpToolsListRequest] =
    new JsonMethodDecoder[McpToolsListRequest]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpToolsListRequest] =
        Right(McpToolsListRequest())

  given mcpToolsCallRequestDecoder: JsonMethodDecoder[McpToolsCallRequest] =
    new JsonMethodDecoder[McpToolsCallRequest]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpToolsCallRequest] =
        params match
          case Some(JArray((obj: JObject) :: Nil)) =>
            for
              name <- (obj \ "name").extractOpt[String].toRight(JsonRpcError.InvalidParams("Missing 'name' parameter"))
              arguments = (obj \ "arguments").toOption
            yield McpToolsCallRequest(name, arguments)
          case _ => Left(JsonRpcError.InvalidParams())

  given mcpResourcesListRequestDecoder: JsonMethodDecoder[McpResourcesListRequest] =
    new JsonMethodDecoder[McpResourcesListRequest]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpResourcesListRequest] =
        Right(McpResourcesListRequest())

  given mcpResourcesReadRequestDecoder: JsonMethodDecoder[McpResourcesReadRequest] =
    new JsonMethodDecoder[McpResourcesReadRequest]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpResourcesReadRequest] =
        params match
          case Some(JArray((obj: JObject) :: Nil)) =>
            (obj \ "uri")
              .extractOpt[String]
              .toRight(JsonRpcError.InvalidParams("Missing 'uri' parameter"))
              .map(McpResourcesReadRequest.apply)
          case _ => Left(JsonRpcError.InvalidParams())

  given mcpPromptsListRequestDecoder: JsonMethodDecoder[McpPromptsListRequest] =
    new JsonMethodDecoder[McpPromptsListRequest]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpPromptsListRequest] =
        Right(McpPromptsListRequest())

  given mcpPromptsGetRequestDecoder: JsonMethodDecoder[McpPromptsGetRequest] =
    new JsonMethodDecoder[McpPromptsGetRequest]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpPromptsGetRequest] =
        params match
          case Some(JArray((obj: JObject) :: Nil)) =>
            for
              name <- (obj \ "name").extractOpt[String].toRight(JsonRpcError.InvalidParams("Missing 'name' parameter"))
              arguments = (obj \ "arguments").toOption
            yield McpPromptsGetRequest(name, arguments)
          case _ => Left(JsonRpcError.InvalidParams())

  // Encoders
  given mcpServerInfoEncoder: JsonEncoder[McpServerInfo] = new JsonEncoder[McpServerInfo]:
    def encodeJson(info: McpServerInfo): JValue =
      ("name" -> info.name) ~ ("version" -> info.version)

  given mcpToolsCapabilityEncoder: JsonEncoder[McpToolsCapability] = new JsonEncoder[McpToolsCapability]:
    def encodeJson(cap: McpToolsCapability): JValue =
      ("listChanged" -> cap.listChanged)

  given mcpResourcesCapabilityEncoder: JsonEncoder[McpResourcesCapability] =
    new JsonEncoder[McpResourcesCapability]:
      def encodeJson(cap: McpResourcesCapability): JValue =
        ("subscribe" -> cap.subscribe) ~ ("listChanged" -> cap.listChanged)

  given mcpPromptsCapabilityEncoder: JsonEncoder[McpPromptsCapability] = new JsonEncoder[McpPromptsCapability]:
    def encodeJson(cap: McpPromptsCapability): JValue =
      ("listChanged" -> cap.listChanged)

  given mcpCapabilitiesEncoder: JsonEncoder[McpCapabilities] = new JsonEncoder[McpCapabilities]:
    def encodeJson(cap: McpCapabilities): JValue =
      ("tools" -> cap.tools.map(mcpToolsCapabilityEncoder.encodeJson)) ~
        ("resources" -> cap.resources.map(mcpResourcesCapabilityEncoder.encodeJson)) ~
        ("prompts" -> cap.prompts.map(mcpPromptsCapabilityEncoder.encodeJson))

  given mcpInitializeResponseEncoder: JsonEncoder[McpInitializeResponse] =
    new JsonEncoder[McpInitializeResponse]:
      def encodeJson(response: McpInitializeResponse): JValue =
        ("protocolVersion" -> response.protocolVersion) ~
          ("capabilities" -> mcpCapabilitiesEncoder.encodeJson(response.capabilities)) ~
          ("serverInfo" -> mcpServerInfoEncoder.encodeJson(response.serverInfo))

  given mcpToolEncoder: JsonEncoder[McpTool] = new JsonEncoder[McpTool]:
    def encodeJson(tool: McpTool): JValue =
      ("name" -> tool.name) ~
        ("description" -> tool.description) ~
        ("inputSchema" -> tool.inputSchema)

  given mcpToolsListResponseEncoder: JsonEncoder[McpToolsListResponse] = new JsonEncoder[McpToolsListResponse]:
    def encodeJson(response: McpToolsListResponse): JValue =
      ("tools" -> response.tools.map(mcpToolEncoder.encodeJson))

  given mcpTextContentEncoder: JsonEncoder[McpTextContent] = new JsonEncoder[McpTextContent]:
    def encodeJson(content: McpTextContent): JValue =
      ("type" -> content.`type`) ~ ("text" -> content.text)

  given mcpToolsCallResponseEncoder: JsonEncoder[McpToolsCallResponse] = new JsonEncoder[McpToolsCallResponse]:
    def encodeJson(response: McpToolsCallResponse): JValue =
      ("content" -> response.content.map(mcpTextContentEncoder.encodeJson)) ~
        ("isError" -> response.isError)

  given mcpResourceEncoder: JsonEncoder[McpResource] = new JsonEncoder[McpResource]:
    def encodeJson(resource: McpResource): JValue =
      ("uri" -> resource.uri) ~
        ("name" -> resource.name) ~
        ("description" -> resource.description) ~
        ("mimeType" -> resource.mimeType)

  given mcpResourcesListResponseEncoder: JsonEncoder[McpResourcesListResponse] =
    new JsonEncoder[McpResourcesListResponse]:
      def encodeJson(response: McpResourcesListResponse): JValue =
        ("resources" -> response.resources.map(mcpResourceEncoder.encodeJson))

  given mcpResourceContentsEncoder: JsonEncoder[McpResourceContents] = new JsonEncoder[McpResourceContents]:
    def encodeJson(contents: McpResourceContents): JValue =
      ("uri" -> contents.uri) ~
        ("mimeType" -> contents.mimeType) ~
        ("text" -> contents.text)

  given mcpResourcesReadResponseEncoder: JsonEncoder[McpResourcesReadResponse] =
    new JsonEncoder[McpResourcesReadResponse]:
      def encodeJson(response: McpResourcesReadResponse): JValue =
        ("contents" -> response.contents.map(mcpResourceContentsEncoder.encodeJson))

  given mcpPromptArgumentEncoder: JsonEncoder[McpPromptArgument] = new JsonEncoder[McpPromptArgument]:
    def encodeJson(arg: McpPromptArgument): JValue =
      ("name" -> arg.name) ~
        ("description" -> arg.description) ~
        ("required" -> arg.required)

  given mcpPromptEncoder: JsonEncoder[McpPrompt] = new JsonEncoder[McpPrompt]:
    def encodeJson(prompt: McpPrompt): JValue =
      ("name" -> prompt.name) ~
        ("description" -> prompt.description) ~
        ("arguments" -> prompt.arguments.map(_.map(mcpPromptArgumentEncoder.encodeJson)))

  given mcpPromptsListResponseEncoder: JsonEncoder[McpPromptsListResponse] =
    new JsonEncoder[McpPromptsListResponse]:
      def encodeJson(response: McpPromptsListResponse): JValue =
        ("prompts" -> response.prompts.map(mcpPromptEncoder.encodeJson))

  given mcpPromptsGetResponseEncoder: JsonEncoder[McpPromptsGetResponse] =
    new JsonEncoder[McpPromptsGetResponse]:
      def encodeJson(response: McpPromptsGetResponse): JValue =
        ("description" -> response.description) ~
          ("messages" -> JArray(response.messages))

  // Codecs
  given mcpInitializeCodec: JsonMethodCodec[McpInitializeRequest, McpInitializeResponse] =
    new JsonMethodCodec[McpInitializeRequest, McpInitializeResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpInitializeRequest] =
        mcpInitializeRequestDecoder.decodeJson(params)
      def encodeJson(response: McpInitializeResponse): JValue =
        mcpInitializeResponseEncoder.encodeJson(response)

  given mcpToolsListCodec: JsonMethodCodec[McpToolsListRequest, McpToolsListResponse] =
    new JsonMethodCodec[McpToolsListRequest, McpToolsListResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpToolsListRequest] =
        mcpToolsListRequestDecoder.decodeJson(params)
      def encodeJson(response: McpToolsListResponse): JValue =
        mcpToolsListResponseEncoder.encodeJson(response)

  given mcpToolsCallCodec: JsonMethodCodec[McpToolsCallRequest, McpToolsCallResponse] =
    new JsonMethodCodec[McpToolsCallRequest, McpToolsCallResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpToolsCallRequest] =
        mcpToolsCallRequestDecoder.decodeJson(params)
      def encodeJson(response: McpToolsCallResponse): JValue =
        mcpToolsCallResponseEncoder.encodeJson(response)

  given mcpResourcesListCodec: JsonMethodCodec[McpResourcesListRequest, McpResourcesListResponse] =
    new JsonMethodCodec[McpResourcesListRequest, McpResourcesListResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpResourcesListRequest] =
        mcpResourcesListRequestDecoder.decodeJson(params)
      def encodeJson(response: McpResourcesListResponse): JValue =
        mcpResourcesListResponseEncoder.encodeJson(response)

  given mcpResourcesReadCodec: JsonMethodCodec[McpResourcesReadRequest, McpResourcesReadResponse] =
    new JsonMethodCodec[McpResourcesReadRequest, McpResourcesReadResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpResourcesReadRequest] =
        mcpResourcesReadRequestDecoder.decodeJson(params)
      def encodeJson(response: McpResourcesReadResponse): JValue =
        mcpResourcesReadResponseEncoder.encodeJson(response)

  given mcpPromptsListCodec: JsonMethodCodec[McpPromptsListRequest, McpPromptsListResponse] =
    new JsonMethodCodec[McpPromptsListRequest, McpPromptsListResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpPromptsListRequest] =
        mcpPromptsListRequestDecoder.decodeJson(params)
      def encodeJson(response: McpPromptsListResponse): JValue =
        mcpPromptsListResponseEncoder.encodeJson(response)

  given mcpPromptsGetCodec: JsonMethodCodec[McpPromptsGetRequest, McpPromptsGetResponse] =
    new JsonMethodCodec[McpPromptsGetRequest, McpPromptsGetResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, McpPromptsGetRequest] =
        mcpPromptsGetRequestDecoder.decodeJson(params)
      def encodeJson(response: McpPromptsGetResponse): JValue =
        mcpPromptsGetResponseEncoder.encodeJson(response)
