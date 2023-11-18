resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = var.prefix
  dashboard_body = <<THEREBEDRAGONS
{
  "widgets": [
    {
      "type": "metric",
      "x": 0,
      "y": 0,
      "width": 12,
      "height": 6,
      "properties": {
        "metrics": [
          [
            "${var.prefix}",
            "violations.count"
          ]
        ],
        "period": 300,
        "stat": "Maximum",
        "region": "eu-west-1",
        "title": "Total number of violations"
      }
    },
    {
      "type": "metric",
      "x": 0,
      "y": 0,
      "width": 12,
      "height": 6,
      "properties": {
        "metrics": [
          [
            "${var.prefix}",
            "none-violations.count"
          ]
        ],
        "period": 300,
        "stat": "Maximum",
        "region": "eu-west-1",
        "title": "Total number of non violations"
      }
    },
   {
    "metrics": [
        [
            "${var.prefix}",
            "danger-violation.value"]
    ],
    "period": 300,
    "stat": "Maximum",
    "region": "eu-west-1",
    "title": "DANGER",
    "view": "gauge",
    "yAxis": {
        "left": {
            "min": 0,
            "max": 10
        }
    }
}
  ]
}
THEREBEDRAGONS
}